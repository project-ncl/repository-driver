package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;

import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_GENERIC_HTTP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(TrackingReportMocks.indyPomSha1FromCentral);
        downloads.add(TrackingReportMocks.indyJarFromCentral);
        downloads.add(TrackingReportMocks.indyJarSha1FromCentral);
        report.setDownloads(downloads);

        // when
        Set<StoreKey> genericRepos = new HashSet<>();
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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> uploads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);
        StoreKey promotedBuildsKey = new StoreKey(
                PackageTypeConstants.PKG_TYPE_MAVEN,
                StoreType.hosted,
                tempBuild ? configuration.getTempBuildPromotionTarget() : configuration.getBuildPromotionTarget());

        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyJar));
        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyJar + ".md5"));
        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyJar + ".sha1"));
        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyPom));
        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyPom + ".md5"));
        uploads.add(new TrackedContentEntryDTO(buildKey, AccessChannel.NATIVE, TrackingReportMocks.indyPom + ".sha1"));
        report.setUploads(uploads);

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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(
                new TrackedContentEntryDTO(
                        TrackingReportMocks.ignoredKey,
                        AccessChannel.NATIVE,
                        TrackingReportMocks.indyJar));
        report.setDownloads(downloads);

        // when
        Set<StoreKey> genericRepos = new HashSet<>();
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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String pom1 = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
        downloads.add(new TrackedContentEntryDTO(TrackingReportMocks.centralKey, AccessChannel.NATIVE, pom1));
        downloads.add(new TrackedContentEntryDTO(TrackingReportMocks.toBeIgnoredKey, AccessChannel.NATIVE, pom1));
        downloads.add(new TrackedContentEntryDTO(TrackingReportMocks.notToBeIgnoredKey, AccessChannel.NATIVE, pom1));
        report.setDownloads(downloads);

        // when
        Set<StoreKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(2, sourceTargetPaths.size());
    }

    @Test
    public void verifyUploadedArtifacts() throws RepositoryDriverException {
        // given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> uploads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

        TrackedContentEntryDTO trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        uploads.add(trackedIndyJar);

        TrackedContentEntryDTO trackedIndyPom = mavenEntry(buildContentId, TrackingReportMocks.indyPom, "originPomUrl");
        uploads.add(trackedIndyPom);

        report.setUploads(uploads);

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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

        TrackedContentEntryDTO trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        downloads.add(trackedIndyJar);

        String originPomUrl = "originPomUrl";
        TrackedContentEntryDTO trackedIndyPom = mavenEntry(buildContentId, TrackingReportMocks.indyPom, originPomUrl);
        downloads.add(trackedIndyPom);

        report.setDownloads(downloads);

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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-Y";
        String noFileExtensionUrl = "originNoFileExtensionUrl";
        // NCL-7238: Handle urls with no file extension
        TrackedContentEntryDTO trackedNoFileExtensionArtifact = mavenEntry(
                buildContentId,
                TrackingReportMocks.noFileExtensionArtifact,
                noFileExtensionUrl);

        downloads.add(trackedNoFileExtensionArtifact);

        report.setDownloads(downloads);

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
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-X";
        TrackedContentEntryDTO trackedIndyJar = genericProxyEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl");
        downloads.add(trackedIndyJar);

        report.setDownloads(downloads);
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 0);
    }

    @Test
    void archivalShouldRespectInternalRepos() throws RepositoryDriverException {
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "ignored";
        TrackedContentEntryDTO trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        StoreKey buildKey = trackedIndyJar.getStoreKey();
        downloads.add(trackedIndyJar);

        report.setDownloads(downloads);
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);
        Assertions.assertEquals(entries.get(0).getStoreKey(), buildKey);
    }

    @Test
    void archivalShouldRespectPromotionToSharedImports() throws RepositoryDriverException {
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-x";
        TrackedContentEntryDTO trackedIndyJar = mavenEntry(buildContentId, TrackingReportMocks.indyJar, "originJarUrl");
        downloads.add(trackedIndyJar);

        String buildContentId2 = "build-y";
        TrackedContentEntryDTO trackedIndyNpmArt = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl");
        downloads.add(trackedIndyNpmArt);

        report.setDownloads(downloads);
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(2, entries.size());

        for (ArchiveDownloadEntry entry : entries) {
            Assertions.assertEquals(entry.getStoreKey().getPackageType(), "maven");
            Assertions.assertEquals(entry.getStoreKey().getType(), StoreType.hosted);
            Assertions.assertEquals(entry.getStoreKey().getName(), "shared-imports");
        }
    }

    @Test
    void archivalShouldFilterOutByRepoFilter() throws RepositoryDriverException {
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-x";
        StoreKey storeKey = new StoreKey("maven", StoreType.hosted, "build-xxxxx"); // from app.yaml
        TrackedContentEntryDTO shouldFilter = mavenEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                storeKey);
        downloads.add(shouldFilter);

        String buildContentId2 = "build-y";
        StoreKey storeKey2 = new StoreKey("maven", StoreType.hosted, "build-yyyyy"); // from app.yaml
        TrackedContentEntryDTO shouldFilter2 = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                storeKey2);
        downloads.add(shouldFilter2);

        String buildContentId3 = "build-z";
        StoreKey storeKey3 = new StoreKey("maven", StoreType.hosted, "ignored");
        TrackedContentEntryDTO shouldNotFilter = mavenEntry(
                buildContentId3,
                TrackingReportMocks.indyPom,
                "originJarUrl",
                storeKey3);
        downloads.add(shouldNotFilter);

        report.setDownloads(downloads);
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);

        ArchiveDownloadEntry notFilteredOut = entries.iterator().next();
        assertEquals(notFilteredOut.getPath(), TrackingReportMocks.indyPom);
    }

    @Test
    public void testPromotionPathGeneration() {
        // given
        TrackedContentDTO trackedContent = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String gpRepoName = "docs-oracle-com-build-ABCDEFGH";
        String gpPath = "/javase/8/docs/api";
        String gpOriginUrl = "http://docs.oracle.com/javase/8/docs/api";
        TrackedContentEntryDTO genericProxyEntry = genericProxyEntry("r-" + gpRepoName, gpPath, gpOriginUrl);
        downloads.add(genericProxyEntry);
        StoreKey gpStoreKey = genericProxyEntry.getStoreKey();

        String mavenRepoName = "build-ABCDEFGH";
        String mavenMetadataPath = "/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
        String mavenMetadataOriginUrl = "http://indy.local/api/content/maven/hosted/build-A47MNG4KFVIAY/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
        TrackedContentEntryDTO metadataEntry = mavenEntry(mavenRepoName, mavenMetadataPath, mavenMetadataOriginUrl);
        downloads.add(metadataEntry);

        String jarPath = "/com/fasterxml/jackson/core/jackson-annotations/2.16.0.redhat-00001/jackson-annotations-2.16.0.redhat-00001.jar";
        TrackedContentEntryDTO jarEntry = mavenEntry(mavenRepoName, jarPath, null);
        downloads.add(jarEntry);
        trackedContent.setDownloads(downloads);

        // when
        Set<StoreKey> genericRepos = new HashSet<>();
        PromotionPaths promotionPaths = trackingReportProcessor
                .collectDownloadsPromotions(trackedContent, genericRepos);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        // then
        Assertions.assertEquals(2, sourceTargetPaths.size());

        // Generic repos
        Assertions.assertEquals(1, genericRepos.size());
        assertTrue(genericRepos.contains(gpStoreKey));

        SourceTargetPaths gpToDedicatedRepo = sourceTargetPaths.stream()
                .filter(a -> a.getSource().equals(gpStoreKey))
                .findAny()
                .orElseThrow();
        StoreKey dedicatedRepo = new StoreKey(PKG_TYPE_GENERIC_HTTP, StoreType.hosted, "h-" + gpRepoName);
        Assertions.assertEquals(dedicatedRepo, gpToDedicatedRepo.getTarget());

        Set<String> gpExpectedPaths = new HashSet<>();
        gpExpectedPaths.add(gpPath);
        Assertions.assertLinesMatch(gpExpectedPaths.stream(), gpToDedicatedRepo.getPaths().stream());

        // Maven repos
        SourceTargetPaths mavenToSharedImports = sourceTargetPaths.stream()
                .filter(a -> a.getSource().equals(metadataEntry.getStoreKey()))
                .findAny()
                .orElseThrow();
        Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, mavenToSharedImports.getTarget());

        Set<String> mavenExpectedPaths = new HashSet<>();
        mavenExpectedPaths.add(jarPath);
        Assertions.assertLinesMatch(mavenExpectedPaths.stream(), mavenToSharedImports.getPaths().stream());
    }

    private static TrackedContentEntryDTO genericProxyEntry(String name, String path, String originUrl) {
        StoreKey storeKey = new StoreKey(PKG_TYPE_GENERIC_HTTP, StoreType.remote, name);
        TrackedContentEntryDTO entry = new TrackedContentEntryDTO(storeKey, AccessChannel.GENERIC_PROXY, path);
        entry.setOriginUrl(originUrl);
        entry.setMd5("0bee89b07a248e27c83fc3d5951213c1");
        entry.setSha1("03cfd743661f07975fa2f1220c5194cbaff48451");
        entry.setSha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb");
        return entry;
    }

    private static TrackedContentEntryDTO mavenEntry(String name, String path, String originUrl) {
        StoreKey storeKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, name);
        return mavenEntry(name, path, originUrl, storeKey);
    }

    private static TrackedContentEntryDTO mavenEntry(String name, String path, String originUrl, StoreKey storeKey) {
        TrackedContentEntryDTO entry = new TrackedContentEntryDTO(storeKey, AccessChannel.NATIVE, path);
        entry.setOriginUrl(originUrl);
        entry.setMd5("0bee89b07a248e27c83fc3d5951213c1");
        entry.setSha1("03cfd743661f07975fa2f1220c5194cbaff48451");
        entry.setSha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb");
        return entry;
    }

    @Test
    void parseRpmPathToIdentifier() {
        String path1 = "/org/jboss/pnc/rpm/eap8-apache-sshd/2.14.0.redhat-00003/apache-sshd-2.14.0-3.redhat_00005.1.el8eap.noarch.rpm";
        String identifier1 = "org.jboss.pnc.rpm:eap8-apache-sshd:rpm:2.14.0.redhat-00003:2.14.0-3.redhat_00005.1.el8eap.noarch";

        assertEquals(TrackingReportProcessor.parseRpmPathToGAPVQ(path1).identifier(), identifier1);
    }
}
