package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@QuarkusTest
public class TrackingReportProcessorTest {

    @Inject
    TrackingReportProcessor trackingReportProcessor;

    @Inject
    Configuration configuration;

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

        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyJar);
        uploads.add(trackedIndyJar);

        TrackedContentEntryDTO trackedIndyPom = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyPom);
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

        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyJar);
        trackedIndyJar.setOriginUrl("originJarUrl");
        downloads.add(trackedIndyJar);

        TrackedContentEntryDTO trackedIndyPom = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyPom);
        String originPomUrl = "originPomUrl";
        trackedIndyPom.setOriginUrl(originPomUrl);
        downloads.add(trackedIndyPom);

        report.setDownloads(downloads);

        // when
        List<RepositoryArtifact> artifacts = trackingReportProcessor.collectDownloadedArtifacts(report);

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
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

        // NCL-7238: Handle urls with no file extension
        TrackedContentEntryDTO trackedNoFileExtensionArtifact = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.noFileExtensionArtifact);
        String noFileExtensionUrl = "originNoFileExtensionUrl";
        trackedNoFileExtensionArtifact.setOriginUrl(noFileExtensionUrl);
        downloads.add(trackedNoFileExtensionArtifact);

        report.setDownloads(downloads);

        // when
        List<RepositoryArtifact> artifacts = trackingReportProcessor.collectDownloadedArtifacts(report);

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
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_GENERIC_HTTP, StoreType.remote, buildContentId);
        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.GENERIC_PROXY,
                TrackingReportMocks.indyJar);
        trackedIndyJar.setOriginUrl("originJarUrl");
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
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, buildContentId);
        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyJar);
        trackedIndyJar.setOriginUrl("originJarUrl");
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
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, buildContentId);
        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyJar);
        trackedIndyJar.setOriginUrl("originJarUrl");
        downloads.add(trackedIndyJar);

        String buildContentId2 = "build-y";
        StoreKey buildKey2 = new StoreKey(PackageTypeConstants.PKG_TYPE_NPM, StoreType.remote, buildContentId2);
        TrackedContentEntryDTO trackedIndyNpmArt = new TrackedContentEntryDTO(
                buildKey2,
                AccessChannel.NATIVE,
                TrackingReportMocks.indyPom);
        trackedIndyNpmArt.setOriginUrl("originUrl");
        downloads.add(trackedIndyNpmArt);

        report.setDownloads(downloads);
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 2);

        for (ArchiveDownloadEntry entry : entries) {
            if (entry.getPath().equals(TrackingReportMocks.indyJar)) {
                Assertions.assertEquals(entry.getStoreKey().getPackageType(), "maven");
            } else {
                Assertions.assertEquals(entry.getStoreKey().getPackageType(), "npm");
            }
            Assertions.assertEquals(entry.getStoreKey().getType(), StoreType.hosted);
            Assertions.assertEquals(entry.getStoreKey().getName(), "shared-imports");
        }
    }

}
