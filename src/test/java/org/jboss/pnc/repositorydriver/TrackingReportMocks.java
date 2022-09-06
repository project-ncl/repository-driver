package org.jboss.pnc.repositorydriver;

import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TrackingReportMocks {

    public static StoreKey centralKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "central");
    public static StoreKey sharedImportsKey = new StoreKey(
            PackageTypeConstants.PKG_TYPE_MAVEN,
            StoreType.hosted,
            IndyRepositoryConstants.SHARED_IMPORTS_ID);
    public static StoreKey ignoredKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "ignored");
    public static StoreKey toBeIgnoredKey = new StoreKey(
            PackageTypeConstants.PKG_TYPE_MAVEN,
            StoreType.remote,
            "tobeignored");
    public static StoreKey notToBeIgnoredKey = new StoreKey(
            PackageTypeConstants.PKG_TYPE_MAVEN,
            StoreType.remote,
            "nottobeignored");

    public static String indyPom = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
    public static String noFileExtensionArtifact = "/org/jboss/shrinkwrap/shrinkwrap-api/1.2.6/shrinkwrap-api-1.2.6";
    public static String noFileExtensionArtifactIdentifier = "org.jboss.shrinkwrap:shrinkwrap-api:empty:1.2.6";
    public static String getNoFileExtensionArtifactPurl = "pkg:maven/org.jboss.shrinkwrap/shrinkwrap-api@1.2.6?type=empty";
    public static TrackedContentEntryDTO indyPomFromCentral;

    public static String indyJar = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar";
    public static TrackedContentEntryDTO indyJarFromCentral;

    static {
        indyPomFromCentral = new TrackedContentEntryDTO(
                TrackingReportMocks.centralKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyPom);
        indyJarFromCentral = new TrackedContentEntryDTO(
                TrackingReportMocks.centralKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyJar);
    }
}
