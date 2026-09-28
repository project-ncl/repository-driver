package org.jboss.pnc.repositorydriver;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.common.security.Md5;
import org.jboss.pnc.repositorydriver.constants.RepositoryConstants;

public class ArtifactoryUtils {

    /**
     * Represents the type of Artifactory repository configuration.
     * Combines temporary and virtual/local distinctions into a single type-safe enum.
     */
    public enum RepositoryType {
        /** Local (hosted) repository: {project}-{type}-{buildId} */
        LOCAL(false, false),

        /** Temporary local repository: {project}-{type}-temp-{buildId} */
        LOCAL_TEMP(true, false),

        /** Virtual (group) repository: {project}-{type}-{buildId}-virt */
        VIRTUAL(false, true),

        /** Temporary virtual repository: {project}-{type}-temp-{buildId}-virt */
        VIRTUAL_TEMP(true, true);

        private final boolean includeTemp;
        private final boolean includeVirtual;

        RepositoryType(boolean includeTemp, boolean includeVirtual) {
            this.includeTemp = includeTemp;
            this.includeVirtual = includeVirtual;
        }

        public boolean includesTemp() {
            return includeTemp;
        }

        public boolean includesVirtual() {
            return includeVirtual;
        }
    }

    /**
     * Builds the repository name for Artifactory based repositories.
     * <p>
     * The naming structure follows the order:
     * {@code [<project>-][<type>-][temp-]<name>[-virt]}
     * <p>
     * If {@code buildContentId} contains explicit placeholders ({@code {project}} or {@code {type}}),
     * only the specified elements are included, with the remaining text used as the repository {@code <name>}.
     * If no placeholders are present, both {@code project} and {@code type} are included by default.
     * <p>
     * Examples:
     * 
     * <pre>{@code
     *     Standard LOCAL:                     pnc-mvn-build-ABCDEF
     *     Standard LOCAL_TEMP:                pnc-mvn-temp-build-ABCDEF
     *     Standard VIRTUAL:                   pnc-mvn-build-ABCDEF-virt
     *     Standard VIRTUAL_TEMP:              pnc-mvn-temp-build-ABCDEF-virt
     *     Placeholder "{project}-central":    pnc-central
     *     Placeholder "{project}-central" (LOCAL_TEMP): pnc-temp-central
     *     Placeholder "{project}-{type}-custom": pnc-mvn-custom
     *     Placeholder "{project}-{type}-custom" (LOCAL_TEMP): pnc-mvn-temp-custom
     * }</pre>
     * </p>
     *
     * @param project The project/deployment type name
     * @param buildType Type of the build (e.g. maven)
     * @param buildContentId The BuildId or repository identifier/template
     * @param repoType The repository type configuration
     * @return formatted repository name
     */
    public static String createRepositoryName(
            String project,
            BuildType buildType,
            String buildContentId,
            RepositoryType repoType) {

        boolean hasProjectPlaceholder = buildContentId != null && buildContentId.contains("{project}");
        boolean hasTypePlaceholder = buildContentId != null && buildContentId.contains("{type}");
        boolean hasAnyPlaceholder = hasProjectPlaceholder || hasTypePlaceholder;

        boolean includeProject = !hasAnyPlaceholder || hasProjectPlaceholder;
        boolean includeType = !hasAnyPlaceholder || hasTypePlaceholder;

        String name = buildContentId != null ? buildContentId : "";
        if (hasAnyPlaceholder) {
            name = name.replace("{project}-", "")
                    .replace("{type}-", "");
        }
        if (name.contains("{") || name.contains("}")) {
            throw new IllegalArgumentException(
                    "Invalid or unrecognized placeholder in repository identifier: " + buildContentId);
        }

        List<String> parts = new ArrayList<>();
        if (includeProject) {
            parts.add(project);
        }
        if (includeType) {
            parts.add(TypeConverters.toRepositoryTypeString(buildType.getRepoType()));
        }
        if (repoType.includesTemp()) {
            parts.add("temp");
        }
        if (!name.isEmpty()) {
            parts.add(name);
        }
        if (repoType.includesVirtual()) {
            parts.add("virt");
        }

        return String.join("-", parts);
    }

    /**
     * Generate a human-readable, length-safe Artifactory repository key from a URL.
     * <p>
     * Format: {@code {project}-{host-slug}-{12-char-md5-of-full-url}}
     * <p>
     * The host slug is the hostname with dots replaced by dashes, trimmed at the last
     * dash word-boundary within 28 characters. The 12-character hex suffix is the first
     * 12 characters of the MD5 of the full URL, ensuring uniqueness even when two URLs
     * share the same host but differ in path.
     * <p>
     * Maximum output length: 41 characters (well within JFrog's 58-char remote repo limit
     * and 64-char local repo limit).
     *
     * @param project the associated project
     * @param host the URI host string (e.g. {@code "resources.knopflerfish.org"})
     * @param url the full URL string used as hash input
     * @return a repository key safe for both local and remote Artifactory repositories
     */
    public static String generateRepoIdFromUrl(String project, String host, String url) {
        String hostWithDashes = host.replaceAll("\\.", "-");
        String slug = trimAtDashBoundary(hostWithDashes, 28);
        try {
            String shortHash = Md5.digest(url).substring(0, 12);
            return project + RepositoryConstants.PROXY_REPO + slug + "-" + shortHash;
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandated by the JVM spec and the input is a validated URI string — cannot happen
            throw new IllegalStateException("Failed to compute MD5 for URL: " + url, e);
        }
    }

    /**
     * Trim a dash-delimited string to at most {@code maxLen} characters, cutting at the
     * last dash boundary so that no segment is partially included.
     *
     * @param s the dash-delimited string to trim
     * @param maxLen the maximum character length
     * @return the trimmed string, at most {@code maxLen} characters long
     */
    static String trimAtDashBoundary(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        String truncated = s.substring(0, maxLen);
        int lastDash = truncated.lastIndexOf('-');
        return lastDash > 0 ? truncated.substring(0, lastDash) : truncated;
    }

}

// Made with Bob
