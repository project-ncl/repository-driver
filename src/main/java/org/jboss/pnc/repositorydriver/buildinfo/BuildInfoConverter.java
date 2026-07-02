/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.repositorydriver.buildinfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.repositorydriver.constants.BuildInformationConstants;
import org.jfrog.build.api.Agent;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.Module;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to convert TrackingReport to JFrog Build format.
 *
 * <p>
 * Build is the standard format used by JFrog Artifactory to track build metadata, including artifacts produced and
 * dependencies consumed during a build. This converter transforms PNC's tracking data into this format.
 * </p>
 *
 * <p>
 * A single TrackingReport is converted to a single Build object containing both artifacts (from uploads) and
 * dependencies (from downloads) in one module. This aligns with Artifactory's uploadBuild API which accepts a single
 * Build object. The Build can then be promoted using Artifactory's promoteBuild API.
 * </p>
 *
 * <p>
 * Usage example:
 * </p>
 *
 * <pre>
 * TrackingReport report = trackingServiceClient.getReport(buildContentId);
 *
 * // Convert entire TrackingReport to a single Build object
 * Build build = BuildInfoConverter.fromTrackingReport(
 *         report,
 *         "project-name",
 *         "my-build");
 *
 * // Upload to Artifactory
 * artifactory.builds().uploadBuild(build);
 *
 * // Later, promote the build (artifacts or dependencies can be promoted separately via BuildPromotionRequest)
 * BuildPromotionRequest promotionRequest = new BuildPromotionRequest();
 * promotionRequest.setTargetRepo("releases");
 * artifactory.builds().promoteBuild(build.getName(), build.getNumber(), promotionRequest);
 * </pre>
 *
 * @author <a href="mailto:ncross@redhat.com">Nick Cross</a>
 * @see <a href=
 *      "https://github.com/jfrog/build-info/blob/master/build-info-api/src/main/java/org/jfrog/build/api/Build.java">JFrog
 *      Build API</a>
 * @see <a href="https://github.com/jfrog/build-info-go/blob/main/buildinfo-schema.json">BuildInfo Schema</a>
 * @see <a href="https://www.buildinfo.org/">BuildInfo.org</a>
 */
public class BuildInfoConverter {

    private static final Logger logger = LoggerFactory.getLogger(BuildInfoConverter.class);

    private static final String BUILD_INFO_VERSION = "1.0.1";
    /** NCL-7238: Add this extension to parse for maven urls with no extensions */
    private static final String MAVEN_SUBSTITUTE_EXTENSION = ".empty";

    /**
     * Converts a TrackingReport to a Build object (primary Build).
     * The trackingId from the report is used as the build number.
     *
     * @param report the tracking report containing uploads and downloads (non-generic)
     * @param projectName the project name to set on the Build
     * @param buildName the name of the build
     * @param buildAgentName the name of the build agent (e.g., "Maven", "Gradle")
     * @param buildAgentVersion the version of the build agent
     * @param startTime the build start time in ISO 8601 format
     * @return a Build object containing artifacts (from uploads) and dependencies (from downloads)
     */
    public static Build fromTrackingReport(
            TrackingReport report,
            String projectName,
            String buildName,
            String buildAgentName,
            String buildAgentVersion,
            String startTime) {
        if (report == null) {
            throw new IllegalArgumentException("TrackingReport cannot be null");
        }

        String buildNumber = report.getTrackingID();
        if (buildNumber == null || buildNumber.isEmpty()) {
            throw new IllegalArgumentException("TrackingReport must have a non-empty trackingID");
        }

        Set<TrackedEntry> uploads = report.getUploads();
        Set<TrackedEntry> downloads = report.getDownloads();

        logger.info(
                "Converting TrackingReport (trackingId: {}) to Build: {} uploads, {} downloads",
                buildNumber,
                uploads != null ? uploads.size() : 0,
                downloads != null ? downloads.size() : 0);

        // Set build agent information (the tool that produced the artifacts, e.g., Maven, Gradle)
        BuildAgent buildAgent = new BuildAgent(buildAgentName, buildAgentVersion);

        // Set agent information (the CI server)
        Agent agent = new Agent("PNC-Repository-Driver", BuildInformationConstants.VERSION);

        // Create primary module containing both artifacts and dependencies
        Module module = new Module();
        module.setId(buildName + ":" + buildNumber);

        // Determine module type from uploads (prefer uploads over downloads for type)
        String moduleType = determineModuleType(uploads, downloads);
        module.setType(moduleType);
        module.setArtifacts(convertToArtifacts(uploads));
        module.setDependencies(convertToDependencies(downloads));

        List<Module> modules = new ArrayList<>();
        modules.add(module);

        // Use BuildInfoBuilder for cleaner construction
        return new BuildInfoBuilder(buildName).number(buildNumber)
                .version(BUILD_INFO_VERSION)
                .started(startTime)
                .buildAgent(buildAgent)
                .agent(agent)
                .modules(modules)
                .project(projectName)
                .build();
    }

    /**
     * Creates a separate Build for generic downloads. Generic downloads are stored as dependencies (not artifacts)
     * because they are consumed artifacts, semantically similar to Maven/NPM dependencies.
     *
     * <p>
     * This Build is uploaded and promoted separately from the primary Build to allow independent promotion to the
     * generic-downloads repository.
     * </p>
     *
     * @param genericDownloads the set of generic download entries
     * @param projectName the project name to set on the Build
     * @param buildName the base name for the build
     * @param buildNumber the build number
     * @param buildAgentName the name of the build agent (e.g., "Maven", "Gradle")
     * @param buildAgentVersion the version of the build agent
     * @param startTime the build start time in ISO 8601 format
     * @return a Build containing the generic downloads as dependencies, or null if genericDownloads is empty
     */
    public static Build createGenericDownloadsBuild(
            Set<TrackedEntry> genericDownloads,
            String projectName,
            String buildName,
            String buildNumber,
            String buildAgentName,
            String buildAgentVersion,
            String startTime) {
        if (genericDownloads == null || genericDownloads.isEmpty()) {
            return null;
        }

        logger.info("Creating generic downloads Build with {} generic downloads", genericDownloads.size());

        // Set build agent information (the tool that produced the artifacts, e.g., Maven, Gradle)
        BuildAgent buildAgent = new BuildAgent(buildAgentName, buildAgentVersion);

        // Set agent information (the CI server)
        Agent agent = new Agent("PNC-Repository-Driver", BuildInformationConstants.VERSION);

        // Create module for generic downloads
        Module genericModule = new Module();
        genericModule.setId(buildName + "-generic-downloads:" + buildNumber);
        genericModule.setType("generic");
        // Store generic downloads as DEPENDENCIES (not artifacts) - they are consumed artifacts
        genericModule.setDependencies(convertToDependencies(genericDownloads));

        List<Module> modules = new ArrayList<>();
        modules.add(genericModule);

        // Use BuildInfoBuilder for cleaner construction
        // Build name includes "-generic-downloads" suffix to differentiate from primary Build
        return new BuildInfoBuilder(buildName + "-generic-downloads").number(buildNumber)
                .version(BUILD_INFO_VERSION)
                .started(startTime)
                .buildAgent(buildAgent)
                .agent(agent)
                .modules(modules)
                .project(projectName)
                .build();
    }

    /**
     * Determines the module type from the entries.
     * TODO: ### Should we use
     * https://github.com/jfrog/build-info/blob/master/build-info-api/src/main/java/org/jfrog/build/api/builder/ModuleType.java
     * ?
     *
     * @param uploads upload entries
     * @param downloads download entries
     * @return module type string (maven, npm, generic, etc.)
     */
    private static String determineModuleType(Set<TrackedEntry> uploads, Set<TrackedEntry> downloads) {
        // Prefer uploads for determining type
        if (uploads != null && !uploads.isEmpty()) {
            for (TrackedEntry entry : uploads) {
                if (entry.getRepoId() != null && entry.getRepoId().getPackageType() != null) {
                    return entry.getRepoId().getPackageType().toString().toLowerCase();
                }
            }
        }

        // Fall back to downloads
        if (downloads != null && !downloads.isEmpty()) {
            for (TrackedEntry entry : downloads) {
                if (entry.getRepoId() != null && entry.getRepoId().getPackageType() != null) {
                    return entry.getRepoId().getPackageType().toString().toLowerCase();
                }
            }
        }

        return "generic";
    }

    /**
     * Converts TrackedEntry objects to Build Artifact objects.
     *
     * @param entries the tracked entries representing uploads
     * @return list of Build artifacts
     */
    private static List<Artifact> convertToArtifacts(Set<TrackedEntry> entries) {
        List<Artifact> artifacts = new ArrayList<>();

        if (entries == null) {
            return artifacts;
        }

        for (TrackedEntry entry : entries) {
            Artifact artifact = new Artifact();

            String fileName = extractFileName(entry.getPath());

            // Set type using JFrog's getTypeString algorithm for Maven artifacts
            artifact.setType(getArtifactType(entry));
            artifact.setName(fileName);
            artifact.setSha256(entry.getSha256());
            artifact.setSha1(entry.getSha1());
            artifact.setMd5(entry.getMd5());
            artifact.setOriginalDeploymentRepo(entry.getRepoId().getPath());

            // Use setRemotePath for the path (will be serialized as "path" in JSON)
            artifact.setRemotePath(entry.getPath());

            artifacts.add(artifact);
        }

        logger.debug("Converted {} entries to artifacts", artifacts.size());
        return artifacts;
    }

    /**
     * Converts TrackedEntry objects to Build Dependency objects.
     *
     * @param entries the tracked entries representing downloads
     * @return list of Build dependencies
     */
    private static List<Dependency> convertToDependencies(Set<TrackedEntry> entries) {
        List<Dependency> dependencies = new ArrayList<>();

        if (entries == null) {
            return dependencies;
        }

        for (TrackedEntry entry : entries) {
            Dependency dependency = new Dependency();

            // Set type using JFrog's getTypeString algorithm for Maven artifacts
            dependency.setType(getArtifactType(entry));

            // Use path as ID for dependencies
            dependency.setId(entry.getPath());
            dependency.setSha256(entry.getSha256());
            dependency.setSha1(entry.getSha1());
            dependency.setMd5(entry.getMd5());
            dependencies.add(dependency);
        }

        logger.debug("Converted {} entries to dependencies", dependencies.size());
        return dependencies;
    }

    /**
     * Extracts the file name from a path.
     *
     * @param path the full path
     * @return the file name
     */
    private static String extractFileName(String path) {
        if (path == null) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Extracts the file extension from a filename.
     * Returns the extension without the dot, or "jar" as default for files without extension.
     *
     * @param fileName the file name
     * @return the file extension
     */
    private static String extractFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "jar"; // default
        }

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            String extension = fileName.substring(lastDot + 1);

            // Handle compound extensions like .tar.gz, .source-jar, etc.
            // Look for patterns like "sources.jar", "javadoc.jar", etc.
            int secondLastDot = fileName.lastIndexOf('.', lastDot - 1);
            if (secondLastDot > 0) {
                String compoundExt = fileName.substring(secondLastDot + 1);
                // Common compound extensions
                if (compoundExt.matches(".*\\.(tar\\.gz|source-jar|sources\\.jar|javadoc\\.jar)")) {
                    return compoundExt.replace('.', '-');
                }
            }

            return extension;
        }

        return "jar"; // default for files without extension
    }

    /**
     * Determines artifact type using JFrog's getTypeString algorithm.
     * For Maven artifacts, uses ArtifactPathInfo to extract type, classifier, and extension,
     * then calls BuildInfoExtractorUtils.getTypeString() directly.
     * For other package types, uses file extension.
     *
     * @param entry the tracked entry (upload or download)
     * @return the artifact type string compatible with Artifactory Build API
     */
    private static String getArtifactType(TrackedEntry entry) {
        if (entry.getRepoId().getPackageType() == PackageType.MAVEN) {
            // Use ArtifactPathInfo to extract Maven GAVTC
            ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(entry.getPath());
            if (pathInfo == null) {
                // NCL-7238: handle Maven artifacts without extensions
                pathInfo = ArtifactPathInfo.parse(entry.getPath() + MAVEN_SUBSTITUTE_EXTENSION);
            }

            if (pathInfo != null) {
                String type = pathInfo.getType();
                String classifier = pathInfo.getClassifier();
                String fileName = extractFileName(entry.getPath());
                String extension = extractFileExtension(fileName);

                // Call JFrog's canonical getTypeString method directly
                return BuildInfoExtractorUtils.getTypeString(type, classifier, extension);
            }
        }

        // For NPM and Generic, use file extension
        String fileName = extractFileName(entry.getPath());
        return extractFileExtension(fileName);
    }
}

// Made with Bob
