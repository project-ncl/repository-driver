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
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.enums.BuildCategory;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants;
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

    private static StoreKey centralKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "central");
    private static StoreKey sharedImportsKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, IndyRepositoryConstants.SHARED_IMPORTS_ID);
    private static StoreKey ignoredKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "ignored");
    private static StoreKey toBeIgnoredKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "tobeignored");
    private static StoreKey notToBeIgnoredKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "nottobeignored");

    private static String indyPom = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
    private static TrackedContentEntryDTO indyPomFromCentral;

    private static String indyJar = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar";
    private static TrackedContentEntryDTO indyJarFromCentral;


    @BeforeAll
    public static void beforeAll() {
        indyPomFromCentral = new TrackedContentEntryDTO(
                centralKey,
                AccessChannel.NATIVE,
                indyPom
        );
        indyJarFromCentral = new TrackedContentEntryDTO(
                centralKey,
                AccessChannel.NATIVE,
                indyJar
        );
    }


    @Test
    public void shouldDownloadTwoThenVerifyExtractedArtifactsContainThem() {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        downloads.add(indyPomFromCentral);
        downloads.add(indyJarFromCentral);
        report.setDownloads(downloads);

        //when
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        //then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().get();
        Assertions.assertEquals(centralKey, fromCentralToSharedImports.getSource());
        Assertions.assertEquals(sharedImportsKey, fromCentralToSharedImports.getTarget());

        Set<String> paths = fromCentralToSharedImports.getPaths();
        Set<String> expected = new HashSet<>();
        expected.add(indyPom);
        expected.add(indyPom + ".sha1");
        expected.add(indyPom + ".md5");
        expected.add(indyJar);
        expected.add(indyJar + ".sha1");
        expected.add(indyJar + ".md5");

        Assertions.assertLinesMatch(expected.stream(), paths.stream());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void shouldUploadTwoThenVerifyExtractedArtifactsContainThem(boolean tempBuild) {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> uploads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);
        StoreKey promotedBuildsKey = new StoreKey(
                PackageTypeConstants.PKG_TYPE_MAVEN,
                StoreType.hosted,
                tempBuild ? configuration.getTempBuildPromotionTarget() : configuration.getBuildPromotionTarget());

        uploads.add(new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyJar
        ));
        uploads.add(new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyPom
        ));
        report.setUploads(uploads);

        //when
        PromotionPaths promotionPaths = trackingReportProcessor.collectUploadsPromotions(report, tempBuild, RepositoryType.MAVEN, buildContentId);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        //then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromBuildToPromoted = sourceTargetPaths.stream().findAny().get();
        Assertions.assertEquals(buildKey, fromBuildToPromoted.getSource());
        Assertions.assertEquals(promotedBuildsKey, fromBuildToPromoted.getTarget());

        Set<String> paths = fromBuildToPromoted.getPaths();
        Set<String> expected = new HashSet<>();
        expected.add(indyPom);
        expected.add(indyPom + ".sha1");
        expected.add(indyPom + ".md5");
        expected.add(indyJar);
        expected.add(indyJar + ".sha1");
        expected.add(indyJar + ".md5");

        Assertions.assertLinesMatch(expected.stream(), paths.stream());
    }

    @Test
    public void shouldExcludeInternalRepoByName() throws Exception {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        downloads.add(indyPomFromCentral);
        downloads.add(new TrackedContentEntryDTO(
                ignoredKey,
                AccessChannel.NATIVE,
                indyJar));
        report.setDownloads(downloads);

        //when
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        //then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().get();
        Assertions.assertEquals(centralKey, fromCentralToSharedImports.getSource());
        Assertions.assertEquals(sharedImportsKey, fromCentralToSharedImports.getTarget());
    }

    @Test
    public void shouldExcludeInternalRepoByRegex() {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String pom1 = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
        downloads.add(new TrackedContentEntryDTO(
                centralKey,
                AccessChannel.NATIVE,
                pom1));
        downloads.add(new TrackedContentEntryDTO(
                toBeIgnoredKey,
                AccessChannel.NATIVE,
                pom1));
        downloads.add(new TrackedContentEntryDTO(
                notToBeIgnoredKey,
                AccessChannel.NATIVE,
                pom1));
        report.setDownloads(downloads);

        //when
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();

        //then
        Assertions.assertEquals(2, sourceTargetPaths.size());
    }

    @Test
    public void verifyUploadedArtifacts() throws RepositoryDriverException {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> uploads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyJar
        );
        uploads.add(trackedIndyJar);

        TrackedContentEntryDTO trackedIndyPom = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyPom
        );
        uploads.add(trackedIndyPom);

        report.setUploads(uploads);

        //when
        List<Artifact> artifacts = trackingReportProcessor.collectUploadedArtifacts(report, false, BuildCategory.STANDARD);

        //then
        Assertions.assertEquals(2, artifacts.size());
        Artifact artifact = artifacts.stream().filter(a -> a.getDeployPath().equals(indyPom)).findAny().get();
        Assertions.assertEquals(BuildCategory.STANDARD, artifact.getBuildCategory());
    }

    @Test
    public void verifyDownloadedArtifacts() throws RepositoryDriverException {
        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        String buildContentId = "build-X";
        StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

        TrackedContentEntryDTO trackedIndyJar = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyJar
        );
        trackedIndyJar.setOriginUrl("originJarUrl"); //TODO what should originUrl look like
        downloads.add(trackedIndyJar);

        TrackedContentEntryDTO trackedIndyPom = new TrackedContentEntryDTO(
                buildKey,
                AccessChannel.NATIVE,
                indyPom
        );
        String originPomUrl = "originPomUrl";
        trackedIndyPom.setOriginUrl(originPomUrl);
        downloads.add(trackedIndyPom);

        report.setDownloads(downloads);

        //when
        List<Artifact> artifacts = trackingReportProcessor.collectDownloadedArtifacts(report);

        //then
        Assertions.assertEquals(2, artifacts.size());
        Artifact artifact = artifacts.stream().filter(a -> a.getDeployPath().equals(indyPom)).findAny().get();
        Assertions.assertEquals(originPomUrl, artifact.getOriginUrl());
    }

}
