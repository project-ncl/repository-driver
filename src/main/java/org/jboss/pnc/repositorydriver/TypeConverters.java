package org.jboss.pnc.repositorydriver;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.trackingservice.dto.PackageType;

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
                return RepositoryType.GENERIC_PROXY;
            default:
                return RepositoryType.GENERIC_PROXY;
        }
    }

    public static RepositoryType toRepoType(String packageType) {
        switch (packageType) {
            case MAVEN_PKG_KEY:
                return RepositoryType.MAVEN;
            case NPM_PKG_KEY:
                return RepositoryType.NPM;
            case GENERIC_PKG_KEY:
                return RepositoryType.GENERIC_PROXY;
            default:
                return RepositoryType.GENERIC_PROXY;
        }
    }

    public static String getIndyPackageTypeKey(RepositoryType repoType) {
        switch (repoType) {
            case MAVEN:
                return MAVEN_PKG_KEY;
            case NPM:
                return NPM_PKG_KEY;
            default:
                throw new IllegalArgumentException(
                        "Repository type " + repoType + " is not supported by this repository manager driver.");
        }
    }

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
}
