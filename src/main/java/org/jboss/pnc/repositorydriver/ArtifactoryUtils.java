package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.enums.RepositoryType;

public class ArtifactoryUtils {
    /**
     * Builds the repository name for Artifactory based repositories using the naming-structure template.
     * <p>
     * Template format: "{project}{temporary}{build}{virtual}{type}"
     * The name might look like e.g.
     *
     * <pre>{@code
     *     pnc-temporary-build-ABCDEF-virtual-maven
     *     pnc-build-ABCDEF-maven
     *     }</pre>
     * </p>
     *
     * @param namingStructure the naming structure template from configuration
     * @param project The project/deployment type name
     * @param buildType Type of the build (e.g. maven)
     * @param isVirtual Whether to create virtual (or group) based repos.
     * @param isTempBuild Whether temporary builds are enabled
     * @param buildContentId The BuildId
     * @return formatted repository name
     */
    public static String createRepositoryName(
            String namingStructure,
            String project,
            BuildType buildType,
            boolean isVirtual,
            boolean isTempBuild,
            String buildContentId) {
        return parseTemplate(
                namingStructure,
                project,
                buildType.getRepoType(),
                isTempBuild ? "temporary" : null,
                isVirtual ? "virtual" : null,
                buildContentId,
                null,
                null);
    }

    /**
     * Parse the download-target-repository template and replace placeholders.
     * Template format: "{project}/{type}/{url}"
     *
     * @param template The template string
     * @param project The project name from RepositoryId
     * @param repoType The repository type (MAVEN, NPM, GENERIC_PROXY)
     * @param originUrl The origin URL from TrackedEntry
     * @return The parsed repository path with placeholders replaced
     */
    public static String parseDownloadTargetRepository(
            String template,
            String project,
            RepositoryType repoType,
            String originUrl) {
        String urlHostname = extractHostnameFromUrl(originUrl);
        return parseTemplate(template, project, repoType, null, null, null, urlHostname, null);
    }

    /**
     * Parse the uploads-target-repository template and replace placeholders.
     * Template format: "{project}/{type}/{target}"
     *
     * @param template The template string
     * @param project The project/deployment type name
     * @param repoType The repository type (MAVEN, NPM)
     * @param buildPromotionTarget The build promotion target name (from getBuildPromotionTarget)
     * @return The parsed repository path with placeholders replaced
     */
    public static String parseUploadsTargetRepository(
            String template,
            String project,
            RepositoryType repoType,
            String buildPromotionTarget) {
        return parseTemplate(template, project, repoType, null, null, null, null, buildPromotionTarget);
    }

    /**
     * Generic template parser that replaces placeholders with actual values.
     * Supports: {project}, {type}, {temporary}, {virtual}, {build}, {url}, {target}
     * Automatically adds dashes between non-empty parts.
     *
     * @param template The template string with placeholders
     * @param project The project/deployment name
     * @param repoType The repository type
     * @param temporary The temporary value (e.g., "temporary" or null)
     * @param virtual The virtual value (e.g., "virtual" or null)
     * @param build The build ID
     * @param url The extracted hostname from URL
     * @param target The build promotion target
     * @return The parsed string with placeholders replaced and dashes added between parts
     */
    private static String parseTemplate(
            String template,
            String project,
            RepositoryType repoType,
            String temporary,
            String virtual,
            String build,
            String url,
            String target) {

        java.util.List<String> parts = new java.util.ArrayList<>();
        String[] placeholders = template.split("\\{|\\}");

        for (String placeholder : placeholders) {
            if (placeholder.isEmpty())
                continue;

            String value = null;
            switch (placeholder) {
                case "project":
                    value = project;
                    break;
                case "type":
                    value = repoType != null ? repoType.name().toLowerCase() : null;
                    break;
                case "temporary":
                    value = temporary;
                    break;
                case "virtual":
                    value = virtual;
                    break;
                case "build":
                    value = build;
                    break;
                case "url":
                    value = url;
                    break;
                case "target":
                    value = target;
                    break;
                default:
                    // Not a placeholder, might be a separator or literal text
                    if (!placeholder.matches("^[{}]+$")) {
                        value = placeholder;
                    }
            }

            if (value != null && !value.isEmpty()) {
                parts.add(value);
            }
        }

        return String.join("-", parts);
    }

    /**
     * Extract hostname from URL and convert dots to dashes.
     *
     * @param originUrl The origin URL
     * @return The hostname with dots replaced by dashes, or "unknown" if parsing fails
     */
    private static String extractHostnameFromUrl(String originUrl) {
        String urlHostname = "unknown";
        if (originUrl != null && !originUrl.isEmpty()) {
            try {
                java.net.URL url = new java.net.URL(originUrl);
                urlHostname = url.getHost().replace(".", "-");
            } catch (java.net.MalformedURLException e) {
                // If URL parsing fails, try to extract hostname manually
                String temp = originUrl;
                // Remove protocol
                if (temp.contains("://")) {
                    temp = temp.substring(temp.indexOf("://") + 3);
                }
                // Extract hostname (before first / or :)
                int slashIdx = temp.indexOf('/');
                int colonIdx = temp.indexOf(':');
                if (slashIdx > 0 && colonIdx > 0) {
                    temp = temp.substring(0, Math.min(slashIdx, colonIdx));
                } else if (slashIdx > 0) {
                    temp = temp.substring(0, slashIdx);
                } else if (colonIdx > 0) {
                    temp = temp.substring(0, colonIdx);
                }
                urlHostname = temp.replace(".", "-");
            }
        }
        return urlHostname;
    }

    // PackageTypes can be [maven, npm, generic-http]
    // TODO: How to handle 'generic-http'? Doesn't match BuildType/RepositoryType.
    public static BuildType parsePackageType(String packageType) {
        if (packageType.equals("maven")) {
            return BuildType.MVN;
        }
        return BuildType.NPM;
    }
}

// Made with Bob
