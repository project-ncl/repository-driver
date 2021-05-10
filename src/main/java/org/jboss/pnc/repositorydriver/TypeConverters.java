package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.RepositoryType;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TypeConverters {

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
}
