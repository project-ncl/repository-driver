package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.BuildType;

public class ArtifactoryUtils {
    /**
     * Builds the repository name for Artifactory based repositories.
     * <p>
     * The name might look like e.g.
     *
     * <pre>{@code
     *     <deployment-type-type>-<build-type>-[<virtual>]-build<ID>
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
    // TODO: ### Use the version from pnc-api
    //    BUT most most of the calls dont have opportunity to create repoobject ? Create a utility in pnc-api Or?
    public static String createRepositoryName(
            Configuration configuration,
            BuildType buildType,
            boolean isVirtual,
            boolean isTempBuild,
            String buildContentId) {
        return configuration.getDeploymentType() + "-" + buildType.getRepoType().name().toLowerCase() + "-" +
                (isVirtual ? "virtual-" : "") + (isTempBuild ? "temporary-" : "") + buildContentId;
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
