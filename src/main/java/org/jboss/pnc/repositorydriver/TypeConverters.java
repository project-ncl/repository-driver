package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jfrog.build.api.builder.ModuleType;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TypeConverters {

    public static RepositoryType toRepoType(PackageType packageType) {
        return switch (packageType) {
            case MAVEN -> RepositoryType.MAVEN;
            case NPM -> RepositoryType.NPM;
            default -> RepositoryType.GENERIC_PROXY;
        };
    }

    /**
     * Convert RepositoryType to PackageType
     */
    public static PackageType toPackageType(RepositoryType repositoryType) {
        return switch (repositoryType) {
            case MAVEN -> PackageType.MAVEN;
            case NPM -> PackageType.NPM;
            case GENERIC_PROXY -> PackageType.GENERIC;
            default -> throw new IllegalArgumentException("Unknown repository type: " + repositoryType);
        };
    }

    /**
     * Convert RepositoryType to lowercase string representation for repository paths.
     * MAVEN is converted to "mvn", other types use their name in lowercase.
     */
    public static String toRepositoryTypeString(RepositoryType repoType) {
        if (repoType == null) {
            return null;
        }
        return repoType == RepositoryType.MAVEN ? "mvn" : repoType.name().toLowerCase();
    }

    /**
     * Convert RepositoryType to JFrog ModuleType for BuildInfo.
     * Uses the official JFrog ModuleType enum values.
     *
     * @param repositoryType the repository type to convert
     * @return the corresponding JFrog ModuleType
     * @see <a href=
     *      "https://github.com/jfrog/build-info/blob/master/build-info-api/src/main/java/org/jfrog/build/api/builder/ModuleType.java">JFrog
     *      ModuleType</a>
     */
    public static ModuleType toModuleType(RepositoryType repositoryType) {
        return switch (repositoryType) {
            case MAVEN -> ModuleType.MAVEN;
            case NPM -> ModuleType.NPM;
            case GENERIC_PROXY -> ModuleType.GENERIC;
            default -> throw new IllegalArgumentException("Unknown repository type: " + repositoryType);
        };
    }
}
