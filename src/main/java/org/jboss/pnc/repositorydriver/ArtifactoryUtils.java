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
     * Format: {project}-{type}-[temp-]{buildId}[-virt]
     * Examples:
     *
     * <pre>{@code
     *     LOCAL:        pnc-mvn-build-ABCDEF
     *     LOCAL_TEMP:   pnc-mvn-temp-build-ABCDEF
     *     VIRTUAL:      pnc-mvn-build-ABCDEF-virt
     *     VIRTUAL_TEMP: pnc-mvn-temp-build-ABCDEF-virt
     *     }</pre>
     * </p>
     *
     * @param project The project/deployment type name
     * @param buildType Type of the build (e.g. maven)
     * @param buildContentId The BuildId
     * @param repoType The repository type configuration
     * @return formatted repository name
     */
    public static String createRepositoryName(
            String project,
            BuildType buildType,
            String buildContentId,
            RepositoryType repoType) {

        List<String> parts = new ArrayList<>();
        // Add parts in order: project, type, temporary (if applicable), build, virtual (if applicable)
        parts.add(project);
        parts.add(TypeConverters.toRepositoryTypeString(buildType.getRepoType()));
        if (repoType.includesTemp()) {
            parts.add("temp");
        }
        parts.add(buildContentId);
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
