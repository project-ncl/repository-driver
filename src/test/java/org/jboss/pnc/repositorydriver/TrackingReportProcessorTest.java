package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.buildinfo.BuildInfoPromotion;
import org.jboss.pnc.repositorydriver.constants.RepositoryConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

    /*
     * @Test
     * public void shouldDownloadTwoThenVerifyExtractedArtifactsContainThem() {
     * // given
     * Set<TrackedEntry> downloads = new HashSet<>();
     * downloads.add(TrackingReportMocks.indyPomFromCentral);
     * downloads.add(TrackingReportMocks.indyPomSha1FromCentral);
     * downloads.add(TrackingReportMocks.indyJarFromCentral);
     * downloads.add(TrackingReportMocks.indyJarSha1FromCentral);
     *
     * TrackingReport report = TrackingReport.builder()
     * .downloads(downloads)
     * .uploads(new HashSet<>())
     * .build();
     *
     * // when
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
     * Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
     *
     * // then
     * Assertions.assertEquals(1, sourceTargetPaths.size());
     *
     * SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().orElseThrow();
     * Assertions.assertEquals(TrackingReportMocks.centralKey, fromCentralToSharedImports.getSource());
     * Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, fromCentralToSharedImports.getTarget());
     *
     * Set<String> paths = fromCentralToSharedImports.getPaths();
     * Set<String> expected = new HashSet<>();
     * expected.add(TrackingReportMocks.indyPom);
     * expected.add(TrackingReportMocks.indyJar);
     *
     * Assertions.assertLinesMatch(expected.stream(), paths.stream());
     * }
     */
    /*
     * @ParameterizedTest
     *
     * @ValueSource(booleans = { true, false })
     * public void shouldUploadTwoThenVerifyExtractedArtifactsContainThem(boolean tempBuild) {
     * // given
     * Set<TrackedEntry> uploads = new HashSet<>();
     *
     * String buildContentId = "build-X";
     * RepositoryKey buildKey = new RepositoryKey(
     * RepositoryId.builder().project("pnc").name(buildContentId).build(),
     * PackageType.MVN,
     * tempBuild);
     * RepositoryKey promotedBuildsKey = new RepositoryKey(
     * RepositoryId.builder()
     * .project("pnc")
     * .name(
     * tempBuild ? configuration.getTempBuildPromotionTarget(BuildCategory.STANDARD)
     * : configuration.getBuildPromotionTarget(BuildCategory.STANDARD))
     * .build(),
     * PackageType.MVN,
     * tempBuild);
     *
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyJar)
     * .build());
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyJar + ".md5")
     * .build());
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyJar + ".sha1")
     * .build());
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyPom)
     * .build());
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyPom + ".md5")
     * .build());
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(buildKey.packageType())
     * .path(TrackingReportMocks.indyPom + ".sha1")
     * .build());
     *
     * TrackingReport report = TrackingReport.builder()
     * .downloads(new HashSet<>())
     * .uploads(uploads)
     * .build();
     *
     * // when
     * PromotionPaths promotionPaths = trackingReportProcessor
     * .collectUploadsPromotions(
     * report,
     * tempBuild,
     * RepositoryType.MAVEN,
     * BuildCategory.STANDARD,
     * buildContentId);
     * Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
     *
     * // then
     * Assertions.assertEquals(1, sourceTargetPaths.size());
     *
     * SourceTargetPaths fromBuildToPromoted = sourceTargetPaths.stream().findAny().orElseThrow();
     * Assertions.assertEquals(buildKey, fromBuildToPromoted.getSource());
     * Assertions.assertEquals(promotedBuildsKey, fromBuildToPromoted.getTarget());
     *
     * Set<String> paths = fromBuildToPromoted.getPaths();
     * Set<String> expected = new HashSet<>();
     * expected.add(TrackingReportMocks.indyPom);
     * expected.add(TrackingReportMocks.indyPom + ".md5");
     * expected.add(TrackingReportMocks.indyJar);
     * expected.add(TrackingReportMocks.indyJar + ".md5");
     *
     * Assertions.assertLinesMatch(expected.stream(), paths.stream());
     * }
     *
     * @Test
     * public void shouldExcludeInternalRepoByName() {
     * // given
     * Set<TrackedEntry> downloads = new HashSet<>();
     *
     * downloads.add(TrackingReportMocks.indyPomFromCentral);
     * downloads.add(
     * TrackedEntry.builder()
     * .repoId(TrackingReportMocks.ignoredKey.repositoryId())
     * .packageType(TrackingReportMocks.ignoredKey.packageType())
     * .path(TrackingReportMocks.indyJar)
     * .build());
     *
     * TrackingReport report = TrackingReport.builder()
     * .downloads(downloads)
     * .uploads(new HashSet<>())
     * .build();
     *
     * // when
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
     * Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
     *
     * // then
     * Assertions.assertEquals(1, sourceTargetPaths.size());
     *
     * SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().orElseThrow();
     * Assertions.assertEquals(TrackingReportMocks.centralKey, fromCentralToSharedImports.getSource());
     * Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, fromCentralToSharedImports.getTarget());
     * }
     */
    /*
     * @Test
     * public void shouldExcludeInternalRepoByRegex() {
     * // given
     * Set<TrackedEntry> downloads = new HashSet<>();
     *
     * String pom1 = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
     * downloads.add(
     * TrackedEntry.builder()
     * .repoId(TrackingReportMocks.centralKey.repositoryId())
     * .packageType(TrackingReportMocks.centralKey.packageType())
     * .path(pom1)
     * .build());
     * downloads.add(
     * TrackedEntry.builder()
     * .repoId(TrackingReportMocks.toBeIgnoredKey.repositoryId())
     * .packageType(TrackingReportMocks.toBeIgnoredKey.packageType())
     * .path(pom1)
     * .build());
     * downloads.add(
     * TrackedEntry.builder()
     * .repoId(TrackingReportMocks.notToBeIgnoredKey.repositoryId())
     * .packageType(TrackingReportMocks.notToBeIgnoredKey.packageType())
     * .path(pom1)
     * .build());
     *
     * TrackingReport report = TrackingReport.builder()
     * .downloads(downloads)
     * .uploads(new HashSet<>())
     * .build();
     *
     * // when
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report, genericRepos);
     * Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
     * // then
     * Assertions.assertEquals(2, sourceTargetPaths.size());
     * }
     */
    @Test
    public void testCreatePromotionBuildInfos_FiltersDownloadsCorrectly() throws RepositoryDriverException {
        // given: TrackingReport with downloads from ignored and non-ignored sources
        Set<TrackedEntry> downloads = new HashSet<>();
        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(TrackingReportMocks.indyJarFromCentral);

        // Add download from ignored source (should be filtered out)
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.ignoredKey)
                        .path("/org/example/artifact/1.0/artifact-1.0.jar")
                        .originUrl("https://example.com/artifact-1.0.jar")
                        .localUrl("file:///tmp/artifact-1.0.jar")
                        .md5("def")
                        .sha1("def")
                        .sha256("def")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when: createPromotionBuildInfo is called
        Set<RepositoryId> genericRepos = new HashSet<>();
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Only non-ignored downloads matching filter patterns are included
        Assertions.assertNotNull(promotion, "Should have BuildInfoPromotion");
        Assertions.assertTrue(promotion.hasDependenciesTarget(), "Should have dependencies target");

        var buildInfo = promotion.primaryBuild();
        var module = buildInfo.getModules().get(0);

        // Should have 2 dependencies (pom and jar from central, not the ignored one)
        Assertions.assertEquals(
                2,
                module.getDependencies().size(),
                "Should have 2 dependencies from non-ignored source");
        Assertions.assertEquals(
                0,
                module.getArtifacts().size(),
                "Should have no artifacts (only downloads)");
    }

    /*
     * @Test
     * public void testCreatePromotionBuildInfos_GroupsByPackageType() throws RepositoryDriverException {
     * // given: TrackingReport with Maven, NPM, and Generic artifacts
     * Set<TrackedEntry> downloads = new HashSet<>();
     * Set<TrackedEntry> uploads = new HashSet<>();
     *
     * // Maven download
     * downloads.add(TrackingReportMocks.indyPomFromCentral);
     *
     * // NPM download
     * RepositoryKey npmCentralKey = new RepositoryKey(
     * RepositoryId.builder().project("pnc").name("npm-central").build(),
     * PackageType.NPM,
     * false);
     * downloads.add(
     * TrackedEntry.builder()
     * .repoId(npmCentralKey.repositoryId())
     * .packageType(PackageType.NPM)
     * .path("/lodash/-/lodash-4.17.21.tgz")
     * .originUrl("https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz")
     * .localUrl("file:///tmp/lodash-4.17.21.tgz")
     * .md5("npm-md5")
     * .sha1("npm-sha1")
     * .sha256("npm-sha256")
     * .build());
     *
     * // Maven upload
     * String buildContentId = "test-build";
     * RepositoryKey buildKey = new RepositoryKey(
     * RepositoryId.builder().project("pnc").name(buildContentId).build(),
     * PackageType.MVN,
     * false);
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(PackageType.MVN)
     * .path("/com/example/myapp/1.0/myapp-1.0.jar")
     * .localUrl("file:///tmp/myapp-1.0.jar")
     * .md5("upload-md5")
     * .sha1("upload-sha1")
     * .sha256("upload-sha256")
     * .build());
     *
     * TrackingReport report = TrackingReport.builder()
     * .trackingID("test-tracking-id")
     * .downloads(downloads)
     * .uploads(uploads)
     * .build();
     *
     * // when: createPromotionBuildInfos is called
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * var buildInfoMap = trackingReportProcessor.createPromotionBuildInfos(
     * report,
     * false,
     * buildContentId,
     * RepositoryType.MAVEN,
     * BuildCategory.STANDARD,
     * genericRepos);
     *
     * // then: Separate BuildInfo objects for each target repository
     * Assertions.assertTrue(
     * buildInfoMap.size() >= 2,
     * "Should have at least 2 BuildInfo objects (maven-shared-imports, npm-shared-imports, and/or build promotion target)"
     * );
     *
     * // Verify we have BuildInfo for different package types
     * boolean hasMavenSharedImports = buildInfoMap.keySet()
     * .stream()
     * .anyMatch(key -> key.repositoryId().getName().equals(RepositoryConstants.MVN_SHARED_IMPORTS_ID));
     * boolean hasNpmSharedImports = buildInfoMap.keySet()
     * .stream()
     * .anyMatch(key -> key.repositoryId().getName().equals(RepositoryConstants.NPM_SHARED_IMPORTS_ID));
     *
     * Assertions.assertTrue(hasMavenSharedImports, "Should have Maven shared-imports BuildInfo");
     * Assertions.assertTrue(hasNpmSharedImports, "Should have NPM shared-imports BuildInfo");
     * }
     */
    @Test
    public void testCreatePromotionBuildInfos_DeterminesModuleNames() throws RepositoryDriverException {
        // given: TrackingReport with Maven and NPM uploads (module name comes from uploads, not downloads)
        Set<TrackedEntry> uploads = new HashSet<>();

        // Maven upload - should extract GAV
        uploads.add(TrackingReportMocks.indyPomFromCentral);

        // NPM upload - should extract package@version
        RepositoryId npmBuildKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.NPM)
                .name("npm-build")
                .build();
        uploads.add(
                TrackedEntry.builder()
                        .repoId(npmBuildKey)
                        .path("/lodash/-/lodash-4.17.21.tgz")
                        .originUrl("https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz")
                        .localUrl("file:///tmp/lodash-4.17.21.tgz")
                        .md5("npm-md5")
                        .sha1("npm-sha1")
                        .sha256("npm-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(new HashSet<>())
                .uploads(uploads)
                .build();

        // when: createPromotionBuildInfo is called
        Set<RepositoryId> genericRepos = new HashSet<>();
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Module names are correctly determined from uploads based on package type
        org.jfrog.build.api.Build buildInfo = promotion.primaryBuild();
        String moduleName = buildInfo.getName();

        // Check based on uploads package type (Maven in this test)
        // Maven module name should contain GAV format (groupId:artifactId:version)
        Assertions.assertTrue(
                moduleName.contains(":"),
                "Maven module name should contain GAV format with colons: " + moduleName);

        // Verify targets are set correctly
        if (promotion.hasArtifactsTarget()) {
            PackageType packageType = promotion.artifactsTarget().getPackageType();
            if (packageType == PackageType.MAVEN) {
                Assertions.assertTrue(
                        moduleName.contains(":"),
                        "Maven module name should contain GAV format with colons: " + moduleName);
            } else if (packageType == PackageType.NPM) {
                Assertions.assertTrue(
                        moduleName.contains("@") || moduleName.contains("lodash"),
                        "NPM module name should contain package name: " + moduleName);
            }
        }
    }

    @Test
    public void testCreatePromotionBuildInfos_HandlesEmptyReport() throws RepositoryDriverException {
        // given: Empty TrackingReport
        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(new HashSet<>())
                .uploads(new HashSet<>())
                .build();

        // when: createPromotionBuildInfo is called
        Set<RepositoryId> genericRepos = new HashSet<>();
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Should have no targets for empty report
        Assertions.assertNotNull(promotion, "Should have BuildInfoPromotion even for empty report");
        Assertions.assertFalse(promotion.hasArtifactsTarget(), "Should have no artifacts target for empty report");
        Assertions
                .assertFalse(promotion.hasDependenciesTarget(), "Should have no dependencies target for empty report");
    }

    @Test
    public void testCreatePromotionBuildInfos_MultipleRepositoriesForMavenAndGeneric()
            throws RepositoryDriverException {
        // given: TrackingReport with Maven downloads, Generic downloads, and Maven uploads
        Set<TrackedEntry> downloads = new HashSet<>();
        Set<TrackedEntry> uploads = new HashSet<>();

        // Maven downloads from central - should go to pnc-mvn-imports
        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(TrackingReportMocks.indyJarFromCentral);

        // Additional Maven downloads from jackson-core
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.centralKey)
                        .path("/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.jar")
                        .originUrl(
                                "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.jar")
                        .localUrl("file:///tmp/jackson-core-2.0.0.jar")
                        .md5("jackson-md5")
                        .sha1("jackson-sha1")
                        .sha256("jackson-sha256")
                        .build());
        downloads.add(
                TrackedEntry.builder()
                        .repoId(TrackingReportMocks.centralKey)
                        .path("/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.pom")
                        .originUrl(
                                "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.pom")
                        .localUrl("file:///tmp/jackson-core-2.0.0.pom")
                        .md5("jackson-pom-md5")
                        .sha1("jackson-pom-sha1")
                        .sha256("jackson-pom-sha256")
                        .build());

        // Generic downloads from different repositories - should go to pnc-generic-downloads
        RepositoryId genericRepo1 = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.GENERIC)
                .name("generic-repo-1")
                .build();
        downloads.add(
                TrackedEntry.builder()
                        .repoId(genericRepo1)
                        .path("/files/archive1.tar.gz")
                        .originUrl("https://example.com/archive1.tar.gz")
                        .localUrl("file:///tmp/archive1.tar.gz")
                        .md5("gen1-md5")
                        .sha1("gen1-sha1")
                        .sha256("gen1-sha256")
                        .build());

        RepositoryId genericRepo2 = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.GENERIC)
                .name("generic-repo-2")
                .build();
        downloads.add(
                TrackedEntry.builder()
                        .repoId(genericRepo2)
                        .path("/files/archive2.zip")
                        .originUrl("https://example.com/archive2.zip")
                        .localUrl("file:///tmp/archive2.zip")
                        .md5("gen2-md5")
                        .sha1("gen2-sha1")
                        .sha256("gen2-sha256")
                        .build());

        // Maven uploads - should go to pnc-mvn-builds (build promotion target)
        RepositoryId mavenUploadRepoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("mvn-builds")
                .build();
        uploads.add(
                TrackedEntry.builder()
                        .repoId(mavenUploadRepoId)
                        .path("/com/example/myapp/1.0/myapp-1.0.jar")
                        .localUrl("file:///tmp/myapp-1.0.jar")
                        .md5("upload-md5")
                        .sha1("upload-sha1")
                        .sha256("upload-sha256")
                        .build());
        uploads.add(
                TrackedEntry.builder()
                        .repoId(mavenUploadRepoId)
                        .path("/com/example/myapp/1.0/myapp-1.0.pom")
                        .localUrl("file:///tmp/myapp-1.0.pom")
                        .md5("upload-pom-md5")
                        .sha1("upload-pom-sha1")
                        .sha256("upload-pom-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-build-id")
                .downloads(downloads)
                .uploads(uploads)
                .build();

        // when: createPromotionBuildInfo is called
        Set<RepositoryId> genericRepos = new HashSet<>();
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Should have two separate Build objects (primary and generic) with multiple targets
        Assertions.assertNotNull(promotion, "Should have BuildInfoPromotion");
        Assertions.assertTrue(promotion.hasArtifactsTarget(), "Should have artifacts target");
        Assertions.assertTrue(promotion.hasDependenciesTarget(), "Should have dependencies target");
        Assertions.assertTrue(promotion.hasGenericDownloads(), "Should have generic downloads target");

        var primaryBuild = promotion.primaryBuild();
        Assertions.assertNotNull(primaryBuild, "Should have primary Build object");
        Assertions.assertEquals(
                "test-build-id",
                primaryBuild.getNumber(),
                "Primary build should have correct tracking ID");
        Assertions.assertEquals(
                1,
                primaryBuild.getModules().size(),
                "Primary build should have 1 module (artifacts + non-generic dependencies)");

        var genericBuild = promotion.genericBuild();
        Assertions.assertNotNull(genericBuild, "Should have generic Build object");
        Assertions.assertEquals(
                "test-build-id",
                genericBuild.getNumber(),
                "Generic build should have same tracking ID as primary");
        Assertions.assertTrue(
                genericBuild.getName().endsWith("-generic-downloads"),
                "Generic build name should have suffix to differentiate from primary, got: " + genericBuild.getName());
        Assertions.assertEquals(
                1,
                genericBuild.getModules().size(),
                "Generic build should have 1 module (generic downloads as dependencies)");

        // Verify Maven shared-imports target (dependencies target)
        Assertions.assertEquals(
                RepositoryConstants.MVN_SHARED_IMPORTS_ID,
                promotion.dependenciesTarget().getName(),
                "Should have Maven shared-imports repository (pnc-mvn-imports)");
        Assertions.assertEquals(
                PackageType.MAVEN,
                promotion.dependenciesTarget().getPackageType(),
                "Dependencies target should be Maven package type");
        Assertions.assertEquals(
                "pnc",
                promotion.dependenciesTarget().getProject(),
                "Project should be 'pnc' from deployment config");

        // Verify artifacts target (build promotion target)
        Assertions.assertNotNull(promotion.artifactsTarget(), "Should have artifacts target");
        Assertions.assertEquals(
                PackageType.MAVEN,
                promotion.artifactsTarget().getPackageType(),
                "Artifacts target should be Maven package type");

        // Verify generic downloads target
        Assertions.assertNotNull(promotion.genericDownloadsTarget(), "Should have generic downloads target");
        Assertions.assertEquals(
                RepositoryConstants.GENERIC_DOWNLOADS,
                promotion.genericDownloadsTarget().getName(),
                "Generic downloads target should be pnc-generic-downloads");
        Assertions.assertEquals(
                PackageType.GENERIC,
                promotion.genericDownloadsTarget().getPackageType(),
                "Generic downloads target should be Generic package type");

        // Verify primary module (contains uploads as artifacts and Maven downloads as dependencies)
        org.jfrog.build.api.Module primaryModule = primaryBuild.getModules().get(0);
        Assertions.assertNotNull(primaryModule, "Should have primary module");
        Assertions.assertNotNull(primaryModule.getArtifacts(), "Primary module should have artifacts list");
        Assertions.assertEquals(
                2,
                primaryModule.getArtifacts().size(),
                "Primary module should have 2 artifacts (jar and pom uploads)");
        // Handle potential null dependencies (Module class may not initialize lists)
        int primaryDepsSize = primaryModule.getDependencies() == null ? 0 : primaryModule.getDependencies().size();
        Assertions.assertEquals(
                4,
                primaryDepsSize,
                "Primary module should have 4 dependencies (indy pom, indy jar, jackson jar, jackson pom)");

        // Verify generic downloads module (contains generic downloads as dependencies, not artifacts)
        org.jfrog.build.api.Module genericModule = genericBuild.getModules().get(0);
        Assertions.assertNotNull(genericModule, "Should have generic downloads module");
        Assertions.assertTrue(
                genericModule.getId().contains("generic-downloads"),
                "Generic module ID should contain 'generic-downloads'");
        // Generic downloads are stored as dependencies (consumed artifacts), not artifacts (produced artifacts)
        int genericDepsSize = genericModule.getDependencies() == null ? 0 : genericModule.getDependencies().size();
        Assertions.assertEquals(
                2,
                genericDepsSize,
                "Generic downloads module should have 2 dependencies (from 2 different repos)");
        // Generic module should have no artifacts (downloads are dependencies, not produced artifacts)
        int genericArtifactsSize = genericModule.getArtifacts() == null ? 0 : genericModule.getArtifacts().size();
        Assertions.assertEquals(
                0,
                genericArtifactsSize,
                "Generic downloads module should have no artifacts (downloads stored as dependencies)");

        // Verify genericRepos collection was populated with source generic repositories
        Assertions.assertEquals(
                2,
                genericRepos.size(),
                "Should have 2 generic source repositories in genericRepos collection");
        Assertions.assertTrue(
                genericRepos.contains(genericRepo1),
                "Should contain generic-repo-1 in genericRepos collection");
        Assertions.assertTrue(
                genericRepos.contains(genericRepo2),
                "Should contain generic-repo-2 in genericRepos collection");
    }

    /*
     * @Test
     * public void testCreatePromotionBuildInfos_CombinesUploadsAndDownloads() throws RepositoryDriverException {
     * // given: TrackingReport with both uploads and downloads for same package type
     * Set<TrackedEntry> downloads = new HashSet<>();
     * Set<TrackedEntry> uploads = new HashSet<>();
     *
     * // Maven download
     * downloads.add(TrackingReportMocks.indyPomFromCentral);
     *
     * // Maven upload
     * String buildContentId = "test-build";
     * RepositoryKey buildKey = new RepositoryKey(
     * RepositoryId.builder().project("pnc").name(buildContentId).build(),
     * PackageType.MVN,
     * false);
     * uploads.add(
     * TrackedEntry.builder()
     * .repoId(buildKey.repositoryId())
     * .packageType(PackageType.MVN)
     * .path("/com/example/myapp/1.0/myapp-1.0.jar")
     * .localUrl("file:///tmp/myapp-1.0.jar")
     * .md5("upload-md5")
     * .sha1("upload-sha1")
     * .sha256("upload-sha256")
     * .build());
     *
     * TrackingReport report = TrackingReport.builder()
     * .trackingID("test-tracking-id")
     * .downloads(downloads)
     * .uploads(uploads)
     * .build();
     *
     * // when: createPromotionBuildInfos is called
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * var buildInfoMap = trackingReportProcessor.createPromotionBuildInfos(
     * report,
     * false,
     * buildContentId,
     * RepositoryType.MAVEN,
     * BuildCategory.STANDARD,
     * genericRepos);
     *
     * // then: Should have separate BuildInfo for downloads (shared-imports) and uploads (build promotion target)
     * Assertions.assertTrue(buildInfoMap.size() >= 1, "Should have at least one BuildInfo");
     *
     * // Verify BuildInfo for shared-imports has dependencies
     * var sharedImportsEntry = buildInfoMap.entrySet()
     * .stream()
     * .filter(e -> e.getKey().repositoryId().getName().equals(RepositoryConstants.MVN_SHARED_IMPORTS_ID))
     * .findFirst();
     *
     * if (sharedImportsEntry.isPresent()) {
     * var module = sharedImportsEntry.get().getValue().getModules().get(0);
     * Assertions.assertTrue(
     * module.getDependencies() != null && !module.getDependencies().isEmpty(),
     * "Shared-imports BuildInfo should have dependencies");
     * }
     *
     * // Verify BuildInfo for build promotion target has artifacts
     * var buildPromotionEntry = buildInfoMap.entrySet()
     * .stream()
     * .filter(
     * e -> e.getKey()
     * .repositoryId()
     * .getName()
     * .equals(configuration.getBuildPromotionTarget(BuildCategory.STANDARD)))
     * .findFirst();
     *
     * if (buildPromotionEntry.isPresent()) {
     * var module = buildPromotionEntry.get().getValue().getModules().get(0);
     * Assertions.assertTrue(
     * module.getArtifacts() != null && !module.getArtifacts().isEmpty(),
     * "Build promotion BuildInfo should have artifacts");
     * }
     * }
     */
    @Test
    public void verifyUploadedArtifacts() throws RepositoryDriverException {
        // given
        Set<TrackedEntry> uploads = new HashSet<>();

        String buildContentId = "build-X";
        RepositoryId buildKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name(buildContentId)
                .build();

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
        RepositoryId buildKey = trackedIndyJar.getRepoId();
        downloads.add(trackedIndyJar);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);
        RepositoryId entryKey = entries.get(0).getRepositoryId();
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

            Assertions.assertEquals(PackageType.MAVEN, entry.getRepositoryId().getPackageType());
            Assertions.assertEquals("pnc-mvn-imports", entry.getRepositoryId().getPath());
        }
    }

    @Test
    void archivalShouldFilterOutByRepoFilter() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();

        String buildContentId = "build-x";
        RepositoryId repositoryKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("build-xxxxx")
                .build(); // from app.yaml
        TrackedEntry shouldFilter = mavenEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey);
        downloads.add(shouldFilter);

        String buildContentId2 = "build-y";
        RepositoryId repositoryKey2 = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("build-yyyyy")
                .build(); // from app.yaml
        TrackedEntry shouldFilter2 = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey2);
        downloads.add(shouldFilter2);

        String buildContentId3 = "build-z";
        RepositoryId repositoryKey3 = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("ignored")
                .build();
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

    /*
     * @Test
     * public void testPromotionPathGeneration() {
     * // given
     * Set<TrackedEntry> downloads = new HashSet<>();
     *
     * String gpRepoName = "docs-oracle-com-build-ABCDEFGH";
     * String gpPath = "/javase/8/docs/api";
     * String gpOriginUrl = "http://docs.oracle.com/javase/8/docs/api";
     * TrackedEntry genericProxyEntry = genericProxyEntry("r-" + gpRepoName, gpPath, gpOriginUrl);
     * downloads.add(genericProxyEntry);
     * RepositoryKey gpRepositoryKey = new RepositoryKey(
     * genericProxyEntry.getRepoId(),
     * genericProxyEntry.getPackageType(),
     * false);
     *
     * String mavenRepoName = "build-ABCDEFGH";
     * String mavenMetadataPath = "/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
     * String mavenMetadataOriginUrl =
     * "http://indy.local/api/content/maven/hosted/build-A47MNG4KFVIAY/com/fasterxml/jackson/datatype/jackson-datatype-jaxrs/maven-metadata.xml";
     * TrackedEntry metadataEntry = mavenEntry(mavenRepoName, mavenMetadataPath, mavenMetadataOriginUrl);
     * downloads.add(metadataEntry);
     *
     * String jarPath =
     * "/com/fasterxml/jackson/core/jackson-annotations/2.16.0.redhat-00001/jackson-annotations-2.16.0.redhat-00001.jar";
     * TrackedEntry jarEntry = mavenEntry(mavenRepoName, jarPath, null);
     * downloads.add(jarEntry);
     *
     * TrackingReport trackedContent = TrackingReport.builder()
     * .downloads(downloads)
     * .uploads(new HashSet<>())
     * .build();
     *
     * // when
     * Set<RepositoryKey> genericRepos = new HashSet<>();
     * PromotionPaths promotionPaths = trackingReportProcessor
     * .collectDownloadsPromotions(trackedContent, genericRepos);
     * Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetsPaths();
     *
     * // then
     * Assertions.assertEquals(2, sourceTargetPaths.size());
     *
     * // Generic repos
     * Assertions.assertEquals(1, genericRepos.size());
     * assertTrue(genericRepos.contains(gpRepositoryKey));
     *
     * SourceTargetPaths gpToDedicatedRepo = sourceTargetPaths.stream()
     * .filter(a -> a.getSource().equals(gpRepositoryKey))
     * .findAny()
     * .orElseThrow();
     * RepositoryKey dedicatedRepo = new RepositoryKey(
     * RepositoryId.builder().project("pnc").name(RepositoryConstants.GENERIC_DOWNLOADS).build(),
     * PackageType.GENERIC,
     * false);
     * Assertions.assertEquals(dedicatedRepo, gpToDedicatedRepo.getTarget());
     *
     * Set<String> gpExpectedPaths = new HashSet<>();
     * // TODO: ### Was gpExpectedPaths.add(gpPath);
     * gpExpectedPaths.add("r-docs-oracle-com-build-ABCDEFGH/docs.oracle.com/javase/8/docs/api");
     * Assertions.assertLinesMatch(gpExpectedPaths.stream(), gpToDedicatedRepo.getPaths().stream());
     *
     * // Maven repos
     * RepositoryKey metadataRepositoryKey = new RepositoryKey(
     * metadataEntry.getRepoId(),
     * metadataEntry.getPackageType(),
     * false);
     * SourceTargetPaths mavenToSharedImports = sourceTargetPaths.stream()
     * .filter(a -> a.getSource().equals(metadataRepositoryKey))
     * .findAny()
     * .orElseThrow();
     * Assertions.assertEquals(TrackingReportMocks.sharedImportsKey, mavenToSharedImports.getTarget());
     *
     * Set<String> mavenExpectedPaths = new HashSet<>();
     * mavenExpectedPaths.add(jarPath);
     * Assertions.assertLinesMatch(mavenExpectedPaths.stream(), mavenToSharedImports.getPaths().stream());
     * }
     */

    private static TrackedEntry genericProxyEntry(String name, String path, String originUrl) {
        return TrackedEntry.builder()
                .repoId(RepositoryId.builder().project("pnc").packageType(PackageType.GENERIC).name(name).build())
                .path(path)
                .originUrl(originUrl)
                .md5("0bee89b07a248e27c83fc3d5951213c1")
                .sha1("03cfd743661f07975fa2f1220c5194cbaff48451")
                .sha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb")
                .build();
    }

    private static TrackedEntry mavenEntry(String name, String path, String originUrl) {
        RepositoryId repositoryKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name(name)
                .build();
        return mavenEntry(name, path, originUrl, repositoryKey);
    }

    private static TrackedEntry mavenEntry(String name, String path, String originUrl, RepositoryId repositoryKey) {
        return TrackedEntry.builder()
                .repoId(repositoryKey)
                .path(path)
                .originUrl(originUrl)
                .md5("0bee89b07a248e27c83fc3d5951213c1")
                .sha1("03cfd743661f07975fa2f1220c5194cbaff48451")
                .sha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb")
                .build();
    }
}

// Made with Bob
