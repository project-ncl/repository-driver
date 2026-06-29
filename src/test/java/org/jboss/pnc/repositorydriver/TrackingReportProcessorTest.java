package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                        .repoId(TrackingReportMocks.ignoredKey.repositoryId())
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

        // when: createPromotionBuildInfos is called
        Set<RepositoryKey> genericRepos = new HashSet<>();
        var buildInfoMap = trackingReportProcessor.createPromotionBuildInfos(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Only non-ignored downloads matching filter patterns are included
        Assertions.assertEquals(1, buildInfoMap.size(), "Should have one BuildInfo for shared-imports");

        var entry = buildInfoMap.entrySet().iterator().next();
        var buildInfo = entry.getValue();
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
        // given: TrackingReport with Maven (GAV), NPM (package@version), and Generic artifacts
        Set<TrackedEntry> downloads = new HashSet<>();

        // Maven download - should extract GAV
        downloads.add(TrackingReportMocks.indyPomFromCentral);

        // NPM download - should extract package@version
        RepositoryKey npmCentralKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.NPM).name("npm-central").build(),
                PackageType.NPM);
        downloads.add(
                TrackedEntry.builder()
                        .repoId(npmCentralKey.repositoryId())
                        .path("/lodash/-/lodash-4.17.21.tgz")
                        .originUrl("https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz")
                        .localUrl("file:///tmp/lodash-4.17.21.tgz")
                        .md5("npm-md5")
                        .sha1("npm-sha1")
                        .sha256("npm-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when: createPromotionBuildInfos is called
        Set<RepositoryKey> genericRepos = new HashSet<>();
        var buildInfoMap = trackingReportProcessor.createPromotionBuildInfos(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Module names are correctly determined based on package type
        for (var entry : buildInfoMap.entrySet()) {
            var buildInfo = entry.getValue();
            var moduleName = buildInfo.getName();
            var packageType = entry.getKey().packageType();

            if (packageType == PackageType.MAVEN) {
                // Maven module name should contain GAV format (groupId:artifactId:version)
                Assertions.assertTrue(
                        moduleName.contains(":"),
                        "Maven module name should contain GAV format with colons: " + moduleName);
            } else if (packageType == PackageType.NPM) {
                // NPM module name should contain package@version format
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

        // when: createPromotionBuildInfos is called
        Set<RepositoryKey> genericRepos = new HashSet<>();
        var buildInfoMap = trackingReportProcessor.createPromotionBuildInfos(
                report,
                false,
                "test-build-id",
                RepositoryType.MAVEN,
                BuildCategory.STANDARD,
                genericRepos);

        // then: Should return empty map
        Assertions.assertTrue(buildInfoMap.isEmpty(), "Should return empty map for empty report");
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
                        .repoId(TrackingReportMocks.centralKey.repositoryId())
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
                        .repoId(TrackingReportMocks.centralKey.repositoryId())
                        .path("/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.pom")
                        .originUrl(
                                "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.0.0/jackson-core-2.0.0.pom")
                        .localUrl("file:///tmp/jackson-core-2.0.0.pom")
                        .md5("jackson-pom-md5")
                        .sha1("jackson-pom-sha1")
                        .sha256("jackson-pom-sha256")
                        .build());

        // Generic downloads from different repositories - should go to pnc-generic-downloads
        RepositoryKey genericRepo1 = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.GENERIC).name("generic-repo-1").build(),
                PackageType.GENERIC);
        downloads.add(
                TrackedEntry.builder()
                        .repoId(genericRepo1.repositoryId())
                        .path("/files/archive1.tar.gz")
                        .originUrl("https://example.com/archive1.tar.gz")
                        .localUrl("file:///tmp/archive1.tar.gz")
                        .md5("gen1-md5")
                        .sha1("gen1-sha1")
                        .sha256("gen1-sha256")
                        .build());

        RepositoryKey genericRepo2 = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.GENERIC).name("generic-repo-2").build(),
                PackageType.GENERIC);
        downloads.add(
                TrackedEntry.builder()
                        .repoId(genericRepo2.repositoryId())
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

        // when: createPromotionBuildInfos is called
        Set<RepositoryKey> genericRepos = new HashSet<>();
        Map<RepositoryKey, org.jfrog.build.api.Build> buildInfoMap = trackingReportProcessor
                .createPromotionBuildInfos(
                        report,
                        false,
                        "test-build-id",
                        RepositoryType.MAVEN,
                        BuildCategory.STANDARD,
                        genericRepos);

        // then: Should have multiple BuildInfo objects for different target repositories
        Assertions.assertEquals(
                3,
                buildInfoMap.size(),
                "Should have exactly 3 BuildInfo objects: pnc-mvn-imports, pnc-generic-downloads, and pnc-mvn-builds (build promotion target). Got: "
                        + buildInfoMap.size());

        // Verify Maven shared-imports BuildInfo exists with dependencies
        RepositoryKey mvnSharedImportsKey = buildInfoMap.keySet()
                .stream()
                .filter(key -> key.repositoryId().getName().equals(RepositoryConstants.MVN_SHARED_IMPORTS_ID))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(
                mvnSharedImportsKey,
                "Should have Maven shared-imports repository key (pnc-mvn-imports)");
        Assertions.assertEquals(PackageType.MAVEN, mvnSharedImportsKey.packageType());
        Assertions.assertEquals(
                "pnc",
                mvnSharedImportsKey.repositoryId().getProject(),
                "Project should be 'pnc' from deployment config");

        org.jfrog.build.api.Build mvnSharedImportsBuild = buildInfoMap.get(mvnSharedImportsKey);
        Assertions.assertNotNull(mvnSharedImportsBuild, "Should have BuildInfo for Maven shared-imports");
        Assertions.assertEquals(1, mvnSharedImportsBuild.getModules().size());
        org.jfrog.build.api.Module mvnModule = mvnSharedImportsBuild.getModules().get(0);
        Assertions.assertEquals(
                4,
                mvnModule.getDependencies().size(),
                "Maven shared-imports should have 4 dependencies (indy pom, indy jar, jackson jar, jackson pom)");
        Assertions.assertEquals(0, mvnModule.getArtifacts().size(), "Maven shared-imports should have no artifacts");

        // Verify generic-downloads BuildInfo exists with dependencies
        RepositoryKey genericDownloadsKey = buildInfoMap.keySet()
                .stream()
                .filter(key -> key.repositoryId().getName().equals(RepositoryConstants.GENERIC_DOWNLOADS))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(
                genericDownloadsKey,
                "Should have generic-downloads repository key (pnc-generic-downloads)");
        Assertions.assertEquals(PackageType.GENERIC, genericDownloadsKey.packageType());

        org.jfrog.build.api.Build genericDownloadsBuild = buildInfoMap.get(genericDownloadsKey);
        Assertions.assertNotNull(genericDownloadsBuild, "Should have BuildInfo for generic-downloads");
        Assertions.assertEquals(1, genericDownloadsBuild.getModules().size());
        org.jfrog.build.api.Module genericModule = genericDownloadsBuild.getModules().get(0);
        Assertions.assertEquals(
                2,
                genericModule.getDependencies().size(),
                "Generic downloads should have 2 dependencies (from 2 different repos)");
        Assertions.assertEquals(0, genericModule.getArtifacts().size(), "Generic downloads should have no artifacts");

        // Verify build promotion target BuildInfo exists with artifacts (pnc-mvn-builds)
        RepositoryKey buildPromotionKey = buildInfoMap.keySet()
                .stream()
                .filter(
                        key -> !key.repositoryId().getName().equals(RepositoryConstants.MVN_SHARED_IMPORTS_ID)
                                && !key.repositoryId().getName().equals(RepositoryConstants.GENERIC_DOWNLOADS))
                .findFirst()
                .orElse(null);
        Assertions
                .assertNotNull(buildPromotionKey, "Should have build promotion target repository key (pnc-mvn-builds)");
        Assertions.assertEquals(PackageType.MAVEN, buildPromotionKey.packageType());
        // The build promotion target name comes from configuration.getBuildPromotionTarget(BuildCategory.STANDARD)
        // which defaults to "target" in test config, but with project prefix becomes "prod-mvn-target"

        org.jfrog.build.api.Build buildPromotionBuild = buildInfoMap.get(buildPromotionKey);
        Assertions.assertNotNull(buildPromotionBuild, "Should have BuildInfo for build promotion target");
        Assertions.assertEquals(1, buildPromotionBuild.getModules().size());
        org.jfrog.build.api.Module buildModule = buildPromotionBuild.getModules().get(0);
        Assertions.assertEquals(
                2,
                buildModule.getArtifacts().size(),
                "Build promotion target should have 2 artifacts (jar and pom)");
        Assertions.assertEquals(
                0,
                buildModule.getDependencies().size(),
                "Build promotion target should have no dependencies");

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

        // Verify all BuildInfo objects have correct tracking ID
        for (org.jfrog.build.api.Build build : buildInfoMap.values()) {
            Assertions
                    .assertEquals("test-build-id", build.getNumber(), "All builds should have the same tracking ID");
        }
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
        RepositoryKey buildKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.MAVEN).name(buildContentId).build(),
                PackageType.MAVEN);

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
                trackedIndyJar.getRepoId().getPackageType());
        downloads.add(trackedIndyJar);

        TrackingReport report = TrackingReport.builder()
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();
        List<ArchiveDownloadEntry> entries = trackingReportProcessor.collectArchivalArtifacts(report);

        Assertions.assertEquals(entries.size(), 1);
        RepositoryKey entryKey = new RepositoryKey(
                entries.get(0).getRepositoryId(),
                entries.get(0).getRepositoryId().getPackageType());
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
        RepositoryKey repositoryKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.MAVEN).name("build-xxxxx").build(),
                PackageType.MAVEN); // from app.yaml
        TrackedEntry shouldFilter = mavenEntry(
                buildContentId,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey);
        downloads.add(shouldFilter);

        String buildContentId2 = "build-y";
        RepositoryKey repositoryKey2 = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.MAVEN).name("build-yyyyy").build(),
                PackageType.MAVEN); // from app.yaml
        TrackedEntry shouldFilter2 = mavenEntry(
                buildContentId2,
                TrackingReportMocks.indyJar,
                "originJarUrl",
                repositoryKey2);
        downloads.add(shouldFilter2);

        String buildContentId3 = "build-z";
        RepositoryKey repositoryKey3 = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.MAVEN).name("ignored").build(),
                PackageType.MAVEN);
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
        RepositoryKey repositoryKey = new RepositoryKey(
                RepositoryId.builder().project("pnc").packageType(PackageType.MAVEN).name(name).build(),
                PackageType.MAVEN);
        return mavenEntry(name, path, originUrl, repositoryKey);
    }

    private static TrackedEntry mavenEntry(String name, String path, String originUrl, RepositoryKey repositoryKey) {
        return TrackedEntry.builder()
                .repoId(repositoryKey.repositoryId())
                .path(path)
                .originUrl(originUrl)
                .md5("0bee89b07a248e27c83fc3d5951213c1")
                .sha1("03cfd743661f07975fa2f1220c5194cbaff48451")
                .sha256("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb")
                .build();
    }
}

// Made with Bob
