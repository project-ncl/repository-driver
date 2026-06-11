package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.trackingservice.dto.PackageType;
import org.jboss.pnc.api.trackingservice.dto.TrackedEntry;
import org.jboss.pnc.api.trackingservice.dto.TrackingReport;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.constants.RepositoryConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@QuarkusTest
public class TrackingReportProcessorTest {

    @Inject
    TrackingReportProcessor trackingReportProcessor;

    @Inject
    Configuration configuration;

    @Inject
    ArtifactFilterDatabase artifactFilter;

    @BeforeAll
    public static void beforeAll() {
    }

    @Test
    public void shouldDownloadTwoThenVerifyExtractedArtifactsContainThem() {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();
        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(TrackingReportMocks.indyPomSha1FromCentral);
        downloads.add(TrackingReportMocks.indyJarFromCentral);
        downloads.add(TrackingReportMocks.indyJarSha1FromCentral);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        Set<RepositoryKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().orElseThrow();
        Assertions.assertEquals(TrackingReportMocks.centralKey, fromCentralToSharedImports.getSource());
        Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, fromCentralToSharedImports.getTarget());

        Set<String> paths = fromCentralToSharedImports.getPaths();
        Set<String> expected = new HashSet<>();
        expected.add(TrackingReportMocks.indyPom);
        expected.add(TrackingReportMocks.indyJar);

        Assertions.assertLinesMatch(expected.stream(), paths.stream());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void shouldUploadTwoThenVerifyExtractedArtifactsContainThem(boolean tempBuild) {
        // given
        Set<TrackedEntry> uploads = new HashSet<>();

        String buildContentId = "build-X";
        RepositoryKey buildKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").name(buildContentId).build(),
                PackageType.MVN,
                tempBuild);
        RepositoryKey promotedBuildsKey = new RepositoryKey(
                RepositoryId.builder()
                        .project("pnc")
                        .name(
                                tempBuild ? configuration.getTempBuildPromotionTarget()
                                        : configuration.getBuildPromotionTarget())
                        .build(),
                PackageType.MVN,
                tempBuild);

        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyJar)
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyJar + ".md5")
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyJar + ".sha1")
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyPom)
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyPom + ".md5")
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey.repositoryId())
                        .packageType(buildKey.packageType())
                        .path(TrackingReportMocks.indyPom + ".sha1")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .downloads(new HashSet<>())
                .uploads(uploads)
                .build();

        // when
        PromotionPaths promotionPaths = trackingReportProcessor
                .collectUploadsPromotions(report, tempBuild, RepositoryType.MAVEN, buildContentId);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromBuildToPromoted = sourceTargetPaths.stream().findAny().orElseThrow();
        Assertions.assertEquals(buildKey, fromBuildToPromoted.getSource());
        Assertions.assertEquals(promotedBuildsKey, fromBuildToPromoted.getTarget());

        Set<String> paths = fromBuildToPromoted.getPaths();
        Set<String> expected = new HashSet<>();
        expected.add(TrackingReportMocks.indyPom);
        expected.add(TrackingReportMocks.indyPom + ".md5");
        expected.add(TrackingReportMocks.indyJar);
        expected.add(TrackingReportMocks.indyJar + ".md5");

        Assertions.assertLinesMatch(expected.stream(), paths.stream());
    }

    @Test
    public void shouldExcludeInternalRepoByName() {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();

        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.ignoredKey.repositoryId())
                        .packageType(TrackingReportMocks.ignoredKey.packageType())
                        .path(TrackingReportMocks.indyJar)
                        .build());

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        Set<RepositoryKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().orElseThrow();
        Assertions.assertEquals(TrackingReportMocks.centralKey, fromCentralToSharedImports.getSource());
        Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, fromCentralToSharedImports.getTarget());
    }

    @Test
    public void shouldExcludeInternalRepoByRegex() {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();

        String pom1 = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.centralKey.repositoryId())
                        .packageType(TrackingReportMocks.centralKey.packageType())
                        .path(pom1)
                        .build());
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.toBeIgnoredKey.repositoryId())
                        .packageType(TrackingReportMocks.toBeIgnoredKey.packageType())
                        .path(pom1)
                        .build());
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.notToBeIgnoredKey.repositoryId())
                        .packageType(TrackingReportMocks.notToBeIgnoredKey.packageType())
                        .path(pom1)
                        .build());

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        Set<RepositoryKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
        // then
        Assertions.assertEquals(2, sourceTargetPaths.size());
    }

    @Test
    public void verifyUploadedArtifacts() throws RepositoryDriverException {
        // given
        Set<TrackedEntry> uploads = new HashSet<>();

        String buildContentId = "build-X";
        RepositoryKey buildKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").name(buildContentId).build(),
                PackageType.MVN,
                false);

        TrackedEntry trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        uploads.add(trackedIndyJar);

        TrackedEntry trackedIndyPom = mavenEntry(buildContentId, TrackingReportMocks.indyPom, "originPomUrl");
        uploads.add(trackedIndyPom);

        TrackingReport report = TrackingReport.builder()
                .downloads(new HashSet<>())
                .uploads(uploads)
                .build();

        // when
        List<RepositoryArtifact> artifacts = trackingReportProcessor
                .collectUploadedArtifacts(report, false, BuildCategory.STANDARD);

        // then
        Assertions.assertEquals(2, artifacts.size());
        RepositoryArtifact artifact = artifacts.stream()
                .filter(a -> a.getDeployPath().equals(TrackingReportMocks.indyPom))
                .findAny()
                .orElseThrow();
        Assertions.assertEquals(BuildCategory.STANDARD, artifact.getBuildCategory());
    }

    @Test
    public void verifyDownloadedArtifacts() throws RepositoryDriverException {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-X";

        TrackedEntry trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        downloads.add(trackedIndyJar);

        String originPomUrl = "originPomUrl";
        TrackedEntry trackedIndyPom = mavenEntry(buildContentId, TrackingReportMocks.indyPom, originPomUrl);
        downloads.add(trackedIndyPom);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        List<RepositoryArtifact> artifacts = trackingReportProcessor.collectDownloadedArtifacts(report, artifactFilter);

        // then
        Assertions.assertEquals(2, artifacts.size());
        RepositoryArtifact artifact = artifacts.stream()
                .filter(a -> a.getDeployPath().equals(TrackingReportMocks.indyPom))
                .findAny()
                .orElseThrow();
        Assertions.assertEquals(originPomUrl, artifact.getOriginUrl());
    }

    /**
     * Test if we properly handle maven urls with no extension and see if the right identifier and purl are generated
     *
     * @throws RepositoryDriverException
     */
    @Test
    public void verifyNoFileExtensionArtifacts() throws RepositoryDriverException {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-Y";
        String noFileExtensionUrl = "originNoFileExtensionUrl";
        // NCL-7238: Handle urls with no file extension
        TrackedEntry trackedNoFileExtensionArtifact = mavenEntry(
                buildContentId,
                TrackingReportMocks.noFileExtensionArtifact,
                noFileExtensionUrl);

        downloads.add(trackedNoFileExtensionArtifact);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        List<RepositoryArtifact> artifacts = trackingReportProcessor.collectDownloadedArtifacts(report, artifactFilter);

        // then
        Assertions.assertEquals(1, artifacts.size());
        RepositoryArtifact artifact = artifacts.stream()
                .filter(a -> a.getDeployPath().equals(TrackingReportMocks.noFileExtensionArtifact))
                .filter(a -> a.getIdentifier().equals(TrackingReportMocks.noFileExtensionArtifactIdentifier))
                .filter(a -> a.getPurl().equals(TrackingReportMocks.getNoFileExtensionArtifactPurl))
                .findAny()
                .orElseThrow();
    }

    @Test
    void shouldNotArchiveGenericProxyArtifacts() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-X";
        TrackedEntry trackedIndyJar = genericProxyEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl");
        downloads.add(trackedIndyJar);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 0);
    }

    @Test
    void archivalShouldRespectInternalRepos() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "ignored";
        TrackedEntry trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        RepositoryKey buildKey = new RepositoryKey(
                trackedIndyJar.getRepoId(),
                trackedIndyJar.getPackageType(),
                false);
        downloads.add(trackedIndyJar);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);
        RepositoryKey entryKey = new RepositoryKey(
                entries.get(0).getRepositoryId(),
                entries.get(0).getPackageType(),
                false);
        Assertions.assertEquals(entryKey, buildKey);
    }

    @Test
    void archivalShouldRespectPromotionToSharedImports() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-x";
        TrackedEntry trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        downloads.add(trackedIndyJar);

        String buildContentId2 = "build-y";
        TrackedEntry trackedIndyNpmArt = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl");
        downloads.add(trackedIndyNpmArt);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(2, entries.size());

        for (ArchiveDownloadEntry entry : entries) {
            /*
             * Previously each entry was e.g.
             * ArchiveDownloadEntry(storeKey=maven:hosted:shared-imports,
             * path=/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar, md5=0bee89b07a248e27c83fc3d5951213c1,
             * sha256=edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb,
             * sha1=03cfd743661f07975fa2f1220c5194cbaff48451, size=null) mdc:[{}]
             * ArchiveDownloadEntry(storeKey=maven:hosted:shared-imports,
             * path=/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar, md5=0bee89b07a248e27c83fc3d5951213c1,
             * sha256=edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb,
             * sha1=03cfd743661f07975fa2f1220c5194cbaff48451, size=null) mdc:[{}]
             *
             * They are now e.g.
             * ArchiveDownloadEntry(repositoryId=RepositoryId(project=pnc, name=build-x), packageType=MVN,
             * path=/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar, md5=0bee89b07a248e27c83fc3d5951213c1,
             * sha256=edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb,
             * sha1=03cfd743661f07975fa2f1220c5194cbaff48451, size=null) mdc:[{}]
             *
             */
            Log.info("### path " + entry.getRepositoryId().getPath() + " entry is " + entry.toString());

            Assertions.assertEquals(PackageType.MVN, entry.getPackageType());
            Assertions.assertEquals("pnc-mvn-imports", entry.getRepositoryId().getPath());
        }
    }

    @Test
    void archivalShouldFilterOutByRepoFilter() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-x";
        RepositoryKey repositoryKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").name("build-xxxxx").build(),
                PackageType.MVN,
                false); // from app.yaml
        TrackedEntry shouldFilter = mavenEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey);
        downloads.add(shouldFilter);

        String buildContentId2 = "build-y";
        RepositoryKey repositoryKey2 = new RepositoryKey(
                RepositoryId.builder().project("pnc").name("build-yyyyy").build(),
                PackageType.MVN,
                false); // from app.yaml
        TrackedEntry shouldFilter2 = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey2);
        downloads.add(shouldFilter2);

        String buildContentId3 = "build-z";
        RepositoryKey repositoryKey3 = new RepositoryKey(
                RepositoryId.builder().project("pnc").name("ignored").build(),
                PackageType.MVN,
                false);
        TrackedEntry shouldNotFilter = mavenEntry(
                buildContentId3,
                TrackingReportMocks.indyPom,
                "originJarUrl",
                repositoryKey3);
        downloads.add(shouldNotFilter);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);

        ArchiveDownloadEntry notFilteredOut = entries.iterator().next();
        assertEquals(notFilteredOut.getPath(), TrackingReportMocks.indyPom);
    }

    @Test
    public void testPromotionPathGeneration() {
        // given
        Set<TrackedEntry> downloads = new HashSet<>();

        String gpRepoName = "docs-oracle-com-build-ABCDEFGH";
        String gpPath = "/javase/8/docs/api";
        String gpOriginUrl = "http://docs.oracle.com/javase/8/docs/api";
        TrackedEntry genericProxyEntry = genericProxyEntry("r-" + gpRepoName, gpPath, gpOriginUrl);
        downloads.add(genericProxyEntry);
        RepositoryKey gpRepositoryKey = new RepositoryKey(
                genericProxyEntry.getRepoId(),
                genericProxyEntry.getPackageType(),
                false);

        String mavenRepoName = "build-ABCDEFGH";
        String mavenMetadataPath = "/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
        String mavenMetadataOriginUrl = "http://indy.local/api/content/maven/hosted/build-A47MNG4KFVIAY/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
        TrackedEntry metadataEntry = mavenEntry(mavenRepoName, mavenMetadataPath, mavenMetadataOriginUrl);
        downloads.add(metadataEntry);

        String jarPath = "/com/fasterxml/jackson/core/jackson-annotations/2.16.0.redhat-00001/jackson-annotations-2.16.0.redhat-00001.jar";
        TrackedEntry jarEntry = mavenEntry(mavenRepoName, jarPath, null);
        downloads.add(jarEntry);

        TrackingReport trackedContent = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when
        Set<RepositoryKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor
                .collectDownloadsPromotions(trackedContent, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(2, sourceTargetPaths.size());

        // Generic repos
        Assertions.assertEquals(1, genericRepos.size());
        assertTrue(genericRepos.contains(gpRepositoryKey));

        SourceTargetPaths gpToDedicatedRepo = sourceTargetPaths.stream()
                .filter(a -> a.getSource().equals(gpRepositoryKey))
                .findAny()
                .orElseThrow();
        RepositoryKey dedicatedRepo = new RepositoryKey(
                RepositoryId.builder().project("pnc").name(RepositoryConstants.GENERIC_DOWNLOADS).build(),
                PackageType.GENERIC,
                false);
        Assertions.assertEquals(dedicatedRepo, gpToDedicatedRepo.getTarget());

        Set<String> gpExpectedPaths = new HashSet<>();
        // TODO: ### Was gpExpectedPaths.add(gpPath);
        gpExpectedPaths.add("r-docs-oracle-com-build-ABCDEFGH/docs.oracle.com/javase/8/docs/api");
        Assertions.assertLinesMatch(gpExpectedPaths.stream(), gpToDedicatedRepo.getPaths().stream());

        // Maven repos
        RepositoryKey metadataRepositoryKey = new RepositoryKey(
                metadataEntry.getRepoId(),
                metadataEntry.getPackageType(),
                false);
        SourceTargetPaths mavenToSharedImports = sourceTargetPaths.stream()
                .filter(a -> a.getSource().equals(metadataRepositoryKey))
                .findAny()
                .orElseThrow();
        Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, mavenToSharedImports.getTarget());

        Set<String> mavenExpectedPaths = new HashSet<>();
        mavenExpectedPaths.add(jarPath);
        Assertions.assertLinesMatch(mavenExpectedPaths.stream(), mavenToSharedImports.getPaths().stream());
    }

    private static TrackedEntry genericProxyEntry(String name, String path, String originUrl) {
        return TrackedEntry.builder()
                .repoId(RepositoryId.builder().project("pnc").name(name).build())
                .packageType(PackageType.GENERIC)
                .path(path)
                .originUrl(originUrl)
                .md5("0bee89b07a248e27c83fc3d5951213c1")
                .sha1("03cfd743661f07975fa2f1220c5194cbaff48451")
                .sha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb")
                .build();
    }

    private static TrackedEntry mavenEntry(String name, String path, String originUrl) {
        RepositoryKey repositoryKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").name(name).build(),
                PackageType.MVN,
                false);
        return mavenEntry(name, path, originUrl, repositoryKey);
    }

    private static TrackedEntry mavenEntry(String name, String path, String originUrl, RepositoryKey repositoryKey) {
        return TrackedEntry.builder()
                .repoId(repositoryKey.repositoryId())
                .packageType(repositoryKey.packageType())
                .path(path)
                .originUrl(originUrl)
                .md5("0bee89b07a248e27c83fc3d5951213c1")
                .sha1("03cfd743661f07975fa2f1220c5194cbaff48451")
                .sha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb")
                .build();
    }
}

// Made with Bob
