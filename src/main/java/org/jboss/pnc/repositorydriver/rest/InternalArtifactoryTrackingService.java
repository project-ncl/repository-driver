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
import org.jfrog.artifactory.client.model.RepoPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * TEMPORARY internal implementation of TrackingServiceClient.
 * Queries Artifactory directly using property-based search until external tracking service is ready.
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

    private static final String BUILD_PROPERTY_PREFIX = "pnc.build-";

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
            // Extract the build ID without "build-" prefix for property name
            String buildId = buildContentId.startsWith("build-")
                    ? buildContentId.substring(6)
                    : buildContentId;
            String propertyName = BUILD_PROPERTY_PREFIX + buildId;

            logger.debug("Searching all repositories for artifacts with property: {}", propertyName);

            // Search ALL repositories for artifacts with this property
            // We can't specify repository names because we don't know them ahead of time
            List<RepoPath> allItems = artifactory.searches()
                    .itemsByProperty()
                    .property(propertyName)
                    .doSearch();

            logger.debug(
                    "Found {} total items with property {} across all repositories",
                    allItems.size(),
                    propertyName);

            // Split results based on repoKey:
            // - If repoKey contains buildId → upload (build repository)
            // - If repoKey does NOT contain buildId → download (shared-imports repository)
            Set<TrackedEntry> downloads = new HashSet<>();
            Set<TrackedEntry> uploads = new HashSet<>();

            for (RepoPath repoPath : allItems) {
                try {
                    String repoKey = repoPath.getRepoKey();
                    PackageType packageType = detectPackageType(repoKey);
                    TrackedEntry entry = convertToTrackedEntry(repoPath, packageType);

                    // Determine if this is an upload or download based on repoKey
                    if (repoKey.contains(buildId)) {
                        logger.debug("Classified as UPLOAD: {} (repoKey contains buildId)", repoKey);
                        uploads.add(entry);
                    } else {
                        logger.debug("Classified as DOWNLOAD: {} (repoKey does not contain buildId)", repoKey);
                        downloads.add(entry);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to convert item {}: {}", repoPath.getItemPath(), e.getMessage());
                }
            }

            logger.info(
                    "Internal tracking: Found {} downloads, {} uploads for build {}",
                    downloads.size(),
                    uploads.size(),
                    LogSanitizer.clean(buildContentId));

            if (uploads.isEmpty()) {
                String errorMsg = String.format(
                        "No uploads found with property %s. " +
                                "Ensure artifacts are tagged with build.pnc.* property during build.",
                        LogSanitizer.clean(propertyName));
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
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

    TrackedEntry convertToTrackedEntry(RepoPath repoPath, PackageType packageType) {
        String repoKey = repoPath.getRepoKey();
        String path = repoPath.getItemPath();

        logger.debug(
                "Converting RepoPath to TrackedEntry: repo={}, path={}, packageType={}",
                repoKey,
                path,
                packageType);

        try {
            // Get file info with checksums
            logger.debug("Fetching file info for {}/{}", repoKey, path);
            org.jfrog.artifactory.client.model.File fileInfo = artifactory.repository(repoKey)
                    .file(path)
                    .info();

            // Strip project prefix from repoKey to get the repository name
            // repoKey format: "pnc-mvn-build-123" -> name should be "mvn-build-123"
            String project = configuration.getDeploymentType().toString();
            String repoName = repoKey;
            if (repoKey.startsWith(project + "-")) {
                repoName = repoKey.substring(project.length() + 1);
                logger.debug("Stripped project prefix '{}' from repoKey: {} -> {}", project, repoKey, repoName);
            }

            // Build RepositoryId with packageType
            RepositoryId repoId = RepositoryId.builder()
                    .project(project)
                    .packageType(packageType)
                    .name(repoName)
                    .build();

            // Get checksums (may be null)
            String sha256 = fileInfo.getChecksums() != null ? fileInfo.getChecksums().getSha256() : null;
            String sha1 = fileInfo.getChecksums() != null ? fileInfo.getChecksums().getSha1() : null;
            String md5 = fileInfo.getChecksums() != null ? fileInfo.getChecksums().getMd5() : null;

            if (sha256 == null && sha1 == null) {
                logger.warn("No checksums available for {}/{}", repoKey, path);
            }

            // Build download URL
            String localUrl = configuration.getArtifactoryUrl() + "/" + repoKey + "/" + path;

            // Try to get origin URL from properties, fall back to local URL
            String originUrl = getOriginUrl(repoKey, path, localUrl);

            logger.debug("URLs for {}/{}: local={}, origin={}", repoKey, path, localUrl, originUrl);

            TrackedEntry entry = TrackedEntry.builder()
                    .repoId(repoId)
                    .path(path)
                    .size(fileInfo.getSize())
                    .sha256(sha256)
                    .sha1(sha1)
                    .md5(md5)
                    .localUrl(localUrl)
                    .originUrl(originUrl)
                    .build();

            logger.debug("Successfully converted {}/{} to TrackedEntry (size={})", repoKey, path, fileInfo.getSize());

            return entry;

        } catch (Exception e) {
            logger.error("Failed to convert RepoPath to TrackedEntry: {}/{}", repoKey, path, e);
            throw new RuntimeException("Failed to get file info for " + repoKey + "/" + path, e);
        }
    }

    private String getOriginUrl(String repoKey, String path, String fallbackUrl) {
        try {
            var properties = artifactory.repository(repoKey)
                    .file(path)
                    .getProperties();

            // Check for JFrog origin remote path property
            if (properties != null && properties.containsKey("jf.origin.remote.path")) {
                List<String> values = properties.get("jf.origin.remote.path");
                if (values != null && !values.isEmpty()) {
                    String originUrl = values.get(0);
                    if (originUrl != null && !originUrl.isEmpty()) {
                        return originUrl;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve origin URL for {}/{}: {}", repoKey, path, e.getMessage());
        }

        // Fall back to local URL
        return fallbackUrl;
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

// Made with Bob