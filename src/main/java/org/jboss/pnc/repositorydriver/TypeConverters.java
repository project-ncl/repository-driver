package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.tracker.dto.PackageType;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TypeConverters {

    public static RepositoryType toRepoType(PackageType packageType) {
        switch (packageType) {
            case MVN:
                return RepositoryType.MAVEN;
            case NPM:
                return RepositoryType.NPM;
            case GENERIC:
            default:
                return RepositoryType.GENERIC_PROXY;
        }
    }

    //    public static RepositoryType toRepoType(String packageType) {
    //        switch (packageType) {
    //            case MAVEN_PKG_KEY:
    //                return RepositoryType.MAVEN;
    //            case NPM_PKG_KEY:
    //                return RepositoryType.NPM;
    //            case GENERIC_PKG_KEY:
    //                return RepositoryType.GENERIC_PROXY;
    //            default:
    //                return RepositoryType.GENERIC_PROXY;
    //        }
    //    }

    //    public static String getIndyPackageTypeKey(RepositoryType repoType) {
    //        switch (repoType) {
    //            case MAVEN:
    //                return MAVEN_PKG_KEY;
    //            case NPM:
    //                return NPM_PKG_KEY;
    //            default:
    //                throw new IllegalArgumentException(
    //                        "Repository type " + repoType + " is not supported by this repository manager driver.");
    //        }
    //    }

    /**
     * Convert RepositoryType to PackageType
     */
    public static PackageType toPackageType(RepositoryType repositoryType) {
        switch (repositoryType) {
            case MAVEN:
                return PackageType.MVN;
            case NPM:
                return PackageType.NPM;
            case GENERIC_PROXY:
                return PackageType.GENERIC;
            default:
                throw new IllegalArgumentException("Unknown repository type: " + repositoryType);
        }
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
}
