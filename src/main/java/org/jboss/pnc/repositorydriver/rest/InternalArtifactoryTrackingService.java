/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.repositorydriver.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackDownloadRequest;
import org.jboss.pnc.api.tracker.dto.TrackUploadRequest;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.common.log.LogSanitizer;
import org.jboss.pnc.repositorydriver.Configuration;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.aql.FileSpecBuilder;
import org.jfrog.artifactory.client.model.AqlItem;
import org.jfrog.filespecs.FileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * TEMPORARY internal implementation of TrackingServiceClient.
 * Queries Artifactory directly using a single AQL FileSpec search until the external tracking
 * service is ready.
 *
 * Controlled by: repository-driver.tracking-service.use-internal-tracking
 * When enabled, this bean is injected instead of the REST client.
 *
 * @deprecated This is a temporary QoS solution. Will be removed when external tracking service is deployed.
 */
@Deprecated
@ApplicationScoped
@IfBuildProperty(name = "repository-driver.tracking-service.use-internal-tracking", stringValue = "true")
@Alternative
@Priority(1) // Higher priority than REST client
@RestClient
public class InternalArtifactoryTrackingService implements TrackingServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(InternalArtifactoryTrackingService.class);

    private static final String BUILD_PROPERTY_PREFIX = "pnc.";

    /** Safety ceiling — no single build should exceed this. */
    private static final int AQL_RESULT_LIMIT = 50000;

    @Inject
    Artifactory artifactory;

    @Inject
    Configuration configuration;

    @ConfigProperty(name = "repository-driver.tracking-service.use-internal-tracking", defaultValue = "false")
    boolean useInternalTracking;

    @PostConstruct
    void init() {
        if (useInternalTracking) {
            logger.warn("========================================");
            logger.warn("Using INTERNAL Artifactory-based tracking (temporary QoS solution)");
            logger.warn("This should be replaced with external tracking service in production");
            logger.warn("========================================");
        }
    }

    @Override
    @WithSpan("internal-tracking-get-report")
    public TrackingReport getReport(@SpanAttribute("buildContentId") String buildContentId) {
        if (!useInternalTracking) {
            throw new UnsupportedOperationException(
                    "Internal tracking not enabled. Set repository-driver.tracking-service.use-internal-tracking=true");
        }

        logger.info(
                "Internal tracking: Querying Artifactory for tracking report: {}",
                LogSanitizer.clean(buildContentId));

        try {
            String propertyName = BUILD_PROPERTY_PREFIX + buildContentId;

            logger.debug("Searching all repositories for artifacts with property: {}", propertyName);

            // Single AQL POST — retrieves repo, path, name, checksums, size, and the
            // jf.origin.remote.path property value all in one call.
            FileSpec spec = new FileSpec();
            spec = new FileSpecBuilder()
                    .item("type", "file")
                    .match("repo", configuration.getArtifactoryProject() + "-*")
                    .eq("property.key", propertyName)
                    .include(
                            "name",
                            "repo",
                            "path",
                            "size",
                            "actual_sha1",
                            "actual_md5",
                            "sha256",
                            // Note that searching for a property also acts as a filter and excludes those
                            // without this property hence the second FileGroup search below to find the
                            // uploads that don't have this property.
                            "@jf.origin.remote.path")
                    // TODO: Handle pagination
                    .limit(AQL_RESULT_LIMIT)
                    .addToFileSpec(spec);
            spec = new FileSpecBuilder()
                    .item("type", "file")
                    .match("repo", configuration.getArtifactoryProject() + "-*-" + buildContentId)
                    .eq("property.key", propertyName)
                    .include(
                            "name",
                            "repo",
                            "path",
                            "size",
                            "actual_sha1",
                            "actual_md5",
                            "sha256")
                    // TODO: Handle pagination
                    .limit(AQL_RESULT_LIMIT)
                    .addToFileSpec(spec);

            List<AqlItem> allItems = artifactory.searches().artifactsByFileSpec(spec);

            // Split results based on repoKey:
            // - If repoKey contains buildId → upload (build repository)
            // - If repoKey does NOT contain buildId → download (shared-imports repository)
            Set<TrackedEntry> downloads = new HashSet<>();
            Set<TrackedEntry> uploads = new HashSet<>();

            for (AqlItem item : allItems) {
                String repoKey = item.getRepo();
                TrackedEntry entry = convertAqlItemToTrackedEntry(item, detectPackageType(repoKey));

                if (repoKey.contains(buildContentId)) {
                    uploads.add(entry);
                } else {
                    downloads.add(entry);
                }
            }

            logger.info(
                    "Internal tracking: Across all items {} found {} downloads, {} uploads for build {}",
                    allItems.size(),
                    downloads.size(),
                    uploads.size(),
                    LogSanitizer.clean(buildContentId));

            if (uploads.isEmpty()) {
                logger.error(
                        "No uploads found with property {}. Ensure artifacts are tagged with build.pnc.* property during build.",
                        LogSanitizer.clean(propertyName));
            }

            return TrackingReport.builder()
                    .trackingID(buildContentId)
                    .downloads(downloads)
                    .uploads(uploads)
                    .build();

        } catch (Exception e) {
            logger.error("Internal tracking failed for {}: {}", LogSanitizer.clean(buildContentId), e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve tracking report from Artifactory", e);
        }
    }

    /**
     * Converts an {@link AqlItem} returned by the FileSpec AQL search into a {@link TrackedEntry}.
     * All data is taken directly from the AqlItem — no additional Artifactory calls are made.
     */
    TrackedEntry convertAqlItemToTrackedEntry(AqlItem item, PackageType packageType) {
        String repoKey = item.getRepo();
        // AQL splits the artifact location into a directory (path) and a filename (name).
        // Artifactory returns path with a leading '/' (e.g. "/org/example/lib/1.0"); strip it.
        // When the artifact sits at the repository root, AQL returns path = ".".
        String rawDirectory = item.getPath();
        String directory = (rawDirectory != null && rawDirectory.startsWith("/"))
                ? rawDirectory.substring(1)
                : rawDirectory;
        String name = item.getName();
        String path = (directory == null || directory.isEmpty() || ".".equals(directory))
                ? name
                : directory + "/" + name;

        // logger.debug(
        //         "Converting AqlItem to TrackedEntry: repo={}, path={}, packageType={}",
        //         repoKey,
        //         path,
        //         packageType);

        // Strip project prefix from repoKey to get the repository name
        // repoKey format: "pnc-mvn-build-123" -> name should be "mvn-build-123"
        String project = configuration.getArtifactoryProject();
        String repoName = repoKey;
        if (repoKey.startsWith(project + "-")) {
            repoName = repoKey.substring(project.length() + 1);
        }

        RepositoryId repoId = RepositoryId.builder()
                .project(project)
                .packageType(packageType)
                .name(repoName)
                .build();

        String sha256 = item.getSha256();
        String sha1 = item.getActualSha1();
        String md5 = item.getActualMd5();

        if (sha256 == null && sha1 == null) {
            logger.warn("No checksums available for {}/{}", repoKey, path);
        }

        String localUrl = configuration.getArtifactoryUrl() + "/" + repoKey + "/" + path;
        String originUrl = extractOriginUrl(item, localUrl);

        // logger.debug("URLs for {}/{}: local={}, origin={}", repoKey, path, localUrl, originUrl);

        TrackedEntry entry = TrackedEntry.builder()
                .repoId(repoId)
                .path(path)
                .size(item.getSize())
                .sha256(sha256)
                .sha1(sha1)
                .md5(md5)
                .localUrl(localUrl)
                .originUrl(originUrl)
                .build();

        // logger.debug("Successfully converted {}/{} to TrackedEntry (size={})", repoKey, path, item.getSize());

        return entry;
    }

    /**
     * Extracts the {@code jf.origin.remote.path} property value already embedded in the
     * {@link AqlItem}. Falls back to {@code fallbackUrl} when the property is absent.
     * No additional Artifactory call is made.
     */
    private String extractOriginUrl(AqlItem item, String fallbackUrl) {
        if (item.getProperties() == null) {
            return fallbackUrl;
        }
        return item.getProperties()
                .stream()
                .filter(p -> "jf.origin.remote.path".equals(p.getkey()))
                .map(AqlItem.Property::getValue)
                .filter(v -> v != null && !v.isEmpty())
                .findFirst()
                .orElse(fallbackUrl);
    }

    private PackageType detectPackageType(String repoKey) {
        if (repoKey.contains("-maven-") || repoKey.contains("-mvn")) {
            return PackageType.MAVEN;
        } else if (repoKey.contains("-npm-") || repoKey.contains("-npmjs") || repoKey.contains("-yarnpkg")) {
            return PackageType.NPM;
        } else {
            return PackageType.GENERIC;
        }
    }

    // Minimal implementations for other interface methods
    @Override
    public void initReport(String id) {
        logger.debug("Internal tracking: initReport no-op for {}", LogSanitizer.clean(id));
    }

    @Override
    public void sealReport(String id) {
        logger.debug("Internal tracking: sealReport no-op for {}", LogSanitizer.clean(id));
    }

    @Override
    public void clearReport(String id) {
        logger.debug("Internal tracking: clearReport no-op for {}", LogSanitizer.clean(id));
    }

    @Override
    public List<String> getAllIds(String pattern) {
        logger.debug("Internal tracking: getAllIds not implemented");
        return List.of();
    }

    @Override
    public void trackDownload(String id, TrackDownloadRequest request) {
        logger.debug("Internal tracking: trackDownload no-op");
    }

    @Override
    public void trackUpload(String id, TrackUploadRequest request) {
        logger.debug("Internal tracking: trackUpload no-op");
    }

    @Override
    public List<String> getUploadPaths(String id) {
        logger.debug("Internal tracking: getUploadPaths not implemented");
        return List.of();
    }
}
