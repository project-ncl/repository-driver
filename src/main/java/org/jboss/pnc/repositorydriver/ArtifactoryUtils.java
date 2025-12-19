package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.BuildType;

public class ArtifactoryUtils {
    /**
     * Builds the repository name for Artifactory based repositories.
     * <p>
     * The name might look like e.g.
     *
     * <pre>{@code
     *     <deployment-type>-<build-type>-[<virtual>]-build<ID>
     *     pnc-devel-maven-buildABCDEF
     *     pnc-maven-virtual-buildABCDEF
     *     }</pre>
     * </p>
     *
     * @param configuration the SmallRye config.
     * @param buildType Type of the build (e.g. maven)
     * @param isVirtual Whether to create virtual (or group) based repos.
     * @param isTempBuild Whether temporary builds are enabled
     * @param buildContentId The BuildId
     * @return formatted repository name
     */
    public static String createRepositoryName(
            Configuration configuration,
            BuildType buildType,
            boolean isVirtual,
            boolean isTempBuild, String buildContentId) {
        return configuration.getDeployment() + "-" + buildType.getRepoType().name().toLowerCase() + "-" +
                (isVirtual ? "virtual-" : "") + (isTempBuild ? "temporary-" : "") + buildContentId;
    }
}
