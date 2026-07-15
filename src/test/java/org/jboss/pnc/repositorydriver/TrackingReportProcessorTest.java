package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
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
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:test-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

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
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:test-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

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
    public void testCreatePromotionBuildInfo_UsesBrewBuildNameFromPNCBuild() throws RepositoryDriverException {
        // given: TrackingReport with uploads, but PNC Build has BREW_BUILD_NAME
        Set<TrackedEntry> uploads = new HashSet<>();
        uploads.add(TrackingReportMocks.indyPomFromCentral);

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(new HashSet<>())
                .uploads(uploads)
                .build();

        // when: createPromotionBuildInfo is called with build name from parameters
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-id",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:brew-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

        // then: Module name comes from parameters passed to createPromotionBuildInfo
        org.jfrog.build.api.Build buildInfo = promotion.primaryBuild();
        String moduleName = buildInfo.getName();

        // Module name is passed as parameter: "com.example:brew-artifact"
        Assertions.assertEquals(
                "com.example:brew-artifact:1.0.0",
                moduleName,
                "Module name should come from parameters passed to createPromotionBuildInfo");
    }

    @Test
    public void testCreatePromotionBuildInfo_FallsBackToUploadsWhenNoBrewBuildName() throws RepositoryDriverException {
        // given: TrackingReport with uploads, PNC Build without BREW_BUILD_NAME
        Set<TrackedEntry> uploads = new HashSet<>();

        // Add a Maven upload with clear GAV
        RepositoryId buildKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("build-test")
                .build();
        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildKey)
                        .path("/org/example/mylib/2.0.0/mylib-2.0.0.jar")
                        .localUrl("file:///tmp/mylib-2.0.0.jar")
                        .md5("upload-md5")
                        .sha1("upload-sha1")
                        .sha256("upload-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(new HashSet<>())
                .uploads(uploads)
                .build();

        // when: createPromotionBuildInfo is called with module name from uploads
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "build-without-brew-name",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:fallback-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

        // then: Module name comes from parameters passed to createPromotionBuildInfo
        org.jfrog.build.api.Build buildInfo = promotion.primaryBuild();
        String moduleName = buildInfo.getName();

        Assertions.assertTrue(
                moduleName.contains("com.example:fallback-artifact"),
                "Module name should come from parameters: " + moduleName);
    }

    @Test
    public void testCreatePromotionBuildInfo_ThrowsExceptionWhenNoUploadsAndNoBrewBuildName() {
        // given: TrackingReport with only downloads (no uploads) and no BREW_BUILD_NAME
        Set<TrackedEntry> downloads = new HashSet<>();

        RepositoryId downloadKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("build-X")
                .build();
        downloads.add(
                TrackedEntry.builder()
                        .repoId(downloadKey)
                        .path("/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar")
                        .originUrl(
                                "https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar")
                        .localUrl("file:///tmp/commons-lang3-3.12.0.jar")
                        .md5("download-md5")
                        .sha1("download-sha1")
                        .sha256("download-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(downloads)
                .uploads(new HashSet<>())
                .build();

        // when/then: createPromotionBuildInfo throws exception when no module name provided
        RepositoryDriverException exception = Assertions.assertThrows(
                RepositoryDriverException.class,
                () -> trackingReportProcessor.createPromotionBuildInfo(
                        report,
                        false,
                        "build-without-brew-name",
                        BuildCategory.STANDARD,
                        BuildType.MVN,
                        Instant.now(),
                        null, // No module name provided
                        "1.0.0",
                        Map.of("MAVEN", "3.6.3")),
                "Should throw exception when no module name provided");

        Assertions.assertTrue(
                exception.getMessage().contains("Unable to determine module name"),
                "Exception message should indicate module name determination failure: " + exception.getMessage());
    }

    @Test
    public void testCreatePromotionBuildInfo_ThrowsExceptionWhenNoModuleNameAvailable() {
        // given: TrackingReport with no uploads and no valid downloads
        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(new HashSet<>())
                .uploads(new HashSet<>())
                .build();

        // when/then: createPromotionBuildInfo throws exception
        RepositoryDriverException exception = Assertions.assertThrows(
                RepositoryDriverException.class,
                () -> trackingReportProcessor.createPromotionBuildInfo(
                        report,
                        false,
                        "build-without-brew-name",
                        BuildCategory.STANDARD,
                        BuildType.MVN,
                        Instant.now(),
                        null, // No module name provided
                        "1.0.0",
                        Map.of("MAVEN", "3.6.3")),
                "Should throw exception when module name cannot be determined");

        Assertions.assertTrue(
                exception.getMessage().contains("Unable to determine module name"),
                "Exception message should indicate module name determination failure: " + exception.getMessage());
    }

    @Test
    public void testCreatePromotionBuildInfo_NPMModuleNameFromBrewBuild() throws RepositoryDriverException {
        // given: NPM build with BREW_BUILD_NAME in PNC Build
        Set<TrackedEntry> uploads = new HashSet<>();

        RepositoryId npmBuildKey = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.NPM)
                .name("npm-build")
                .build();
        uploads.add(
                TrackedEntry.builder()
                        .repoId(npmBuildKey)
                        .path("/lodash/-/lodash-4.17.21.tgz")
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

        // when: createPromotionBuildInfo is called for NPM build
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-id",
                BuildCategory.STANDARD,
                BuildType.NPM,
                Instant.now(),
                "@scope/npm-package",
                "1.0.0",
                Map.of("NPM", "8.19.2"));

        // then: Module name comes from parameters passed to createPromotionBuildInfo
        org.jfrog.build.api.Build buildInfo = promotion.primaryBuild();
        String moduleName = buildInfo.getName();

        Assertions.assertEquals(
                "@scope/npm-package:1.0.0",
                moduleName,
                "NPM module name should come from parameters passed to createPromotionBuildInfo");
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
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:test-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

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
        BuildInfoPromotion promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                "test-build-id",
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:test-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

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

    }

    @Test
    public void testCreatePromotionBuildInfo_CombinesUploadsAndDownloads() throws RepositoryDriverException {
        Set<TrackedEntry> downloads = new HashSet<>();
        Set<TrackedEntry> uploads = new HashSet<>();

        downloads.add(TrackingReportMocks.indyPomFromCentral);

        String buildContentId = "test-build";
        RepositoryId buildRepoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name(buildContentId)
                .build();

        uploads.add(
                TrackedEntry.builder()
                        .repoId(buildRepoId)
                        .path("/com/example/myapp/1.0/myapp-1.0.jar")
                        .localUrl("file:///tmp/myapp-1.0.jar")
                        .md5("upload-md5")
                        .sha1("upload-sha1")
                        .sha256("upload-sha256")
                        .build());

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-tracking-id")
                .downloads(downloads)
                .uploads(uploads)
                .build();

        var promotion = trackingReportProcessor.createPromotionBuildInfo(
                report,
                false,
                buildContentId,
                BuildCategory.STANDARD,
                BuildType.MVN,
                Instant.now(),
                "com.example:test-artifact",
                "1.0.0",
                Map.of("MAVEN", "3.6.3"));

        Assertions.assertNotNull(promotion.primaryBuild(), "Primary build should be created");
        Assertions.assertNotNull(promotion.artifactsTarget(), "Artifacts target should be set");
        Assertions.assertNotNull(promotion.dependenciesTarget(), "Dependencies target should be set");

        Assertions.assertEquals(
                "mvn-" + configuration.getBuildPromotionTarget(BuildCategory.STANDARD),
                promotion.artifactsTarget().getName());
        Assertions.assertEquals(RepositoryConstants.MVN_SHARED_IMPORTS_ID, promotion.dependenciesTarget().getName());

        Assertions.assertFalse(
                promotion.primaryBuild().getModules().isEmpty(),
                "Primary build should contain at least one module");

        org.jfrog.build.api.Module module = promotion.primaryBuild().getModules().get(0);

        Assertions.assertTrue(
                module.getArtifacts() != null && !module.getArtifacts().isEmpty(),
                "Primary build module should contain uploaded artifacts");
        Assertions.assertTrue(
                module.getDependencies() != null && !module.getDependencies().isEmpty(),
                "Primary build module should contain downloaded dependencies");
    }

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
