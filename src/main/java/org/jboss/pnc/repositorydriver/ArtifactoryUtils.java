package org.jboss.pnc.repositorydriver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.pnc.api.enums.BuildType;

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

        List<String> parts = new ArrayList<String>();
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
     * Generate MD5 hash of a string and return it as a hex string.
     * Used to create unique, shortened identifiers from URLs.
     *
     * @param input The input string to hash
     * @return The MD5 hash as a hex string, or the input if hashing fails
     */
    public static String generateMd5Hash(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 should always be available, but if not, return the input
            return input;
        }
    }

}

// Made with Bob
