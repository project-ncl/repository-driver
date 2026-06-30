package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.repositorydriver.constants.RepositoryConstants;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class TrackingReportMocks {

    // RepositoryId replacements for old StoreKey
    public static RepositoryId centralKey = RepositoryId.builder()
            .project("pnc")
            .packageType(PackageType.MAVEN)
            .name("central")
            .build();

    public static RepositoryId sharedImportsKey = RepositoryId.builder()
            .project("pnc")
            .packageType(PackageType.MAVEN)
            .name(RepositoryConstants.MVN_SHARED_IMPORTS_ID)
            .build();

    public static RepositoryId ignoredKey = RepositoryId.builder()
            .project("pnc")
            .packageType(PackageType.MAVEN)
            .name("ignored")
            .build();

    public static RepositoryId toBeIgnoredKey = RepositoryId.builder()
            .project("pnc")
            .packageType(PackageType.MAVEN)
            .name("tobeignored")
            .build();

    public static RepositoryId notToBeIgnoredKey = RepositoryId.builder()
            .project("pnc")
            .packageType(PackageType.MAVEN)
            .name("nottobeignored")
            .build();

    public static String indyPom = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
    public static String noFileExtensionArtifact = "/org/jboss/shrinkwrap/shrinkwrap-api/1.2.6/shrinkwrap-api-1.2.6";
    public static String noFileExtensionArtifactIdentifier = "org.jboss.shrinkwrap:shrinkwrap-api:empty:1.2.6";
    public static String getNoFileExtensionArtifactPurl = "pkg:maven/org.jboss.shrinkwrap/shrinkwrap-api@1.2.6?type=empty";
    public static TrackedEntry indyPomFromCentral;
    public static TrackedEntry indyPomSha1FromCentral;

    public static String indyJar = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar";
    public static TrackedEntry indyJarFromCentral;
    public static TrackedEntry indyJarSha1FromCentral;

    static {
        indyPomFromCentral = TrackedEntry.builder()
                .repoId(centralKey)
                .path(TrackingReportMocks.indyPom)
                .originUrl("https://repo.maven.apache.org/maven2" + TrackingReportMocks.indyPom)
                .localUrl("file:///tmp" + TrackingReportMocks.indyPom)
                .md5("abc")
                .sha1("abc")
                .sha256("abc")
                .build();

        indyPomSha1FromCentral = TrackedEntry.builder()
                .repoId(centralKey)
                .path(TrackingReportMocks.indyPom + ".sha1")
                .originUrl("https://repo.maven.apache.org/maven2" + TrackingReportMocks.indyPom + ".sha1")
                .localUrl("file:///tmp" + TrackingReportMocks.indyPom + ".sha1")
                .build();

        indyJarFromCentral = TrackedEntry.builder()
                .repoId(centralKey)
                .path(TrackingReportMocks.indyJar)
                .originUrl("https://repo.maven.apache.org/maven2" + TrackingReportMocks.indyJar)
                .localUrl("file:///tmp" + TrackingReportMocks.indyJar)
                .md5("abc")
                .sha1("abc")
                .sha256("abc")
                .build();

        indyJarSha1FromCentral = TrackedEntry.builder()
                .repoId(centralKey)
                .path(TrackingReportMocks.indyJar + ".sha1")
                .originUrl("https://repo.maven.apache.org/maven2" + TrackingReportMocks.indyJar + ".sha1")
                .localUrl("file:///tmp" + TrackingReportMocks.indyJar + ".sha1")
                .build();
    }
}
