/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.repositorydriver.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.repositorydriver.Configuration;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.RepositoryHandle;
import org.jfrog.artifactory.client.Searches;
import org.jfrog.artifactory.client.model.AqlItem;
import org.jfrog.artifactory.client.model.Repository;
import org.jfrog.artifactory.client.model.impl.PackageTypeImpl;
import org.jfrog.artifactory.client.model.repository.settings.RepositorySettings;
import org.jfrog.filespecs.FileSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InternalArtifactoryTrackingService}.
 *
 * The service is excluded from the CDI context in test profiles (quarkus.arc.exclude-types), so
 * these tests instantiate it directly. All injected fields are package-private, so they are
 * assigned directly — no reflection needed.
 *
 * All Artifactory interactions now go through a single
 * {@link Searches#artifactsByFileSpec(FileSpec)} call; there are no per-artifact
 * {@code .file(path).info()} or {@code .file(path).getProperties()} calls.
 */
public class InternalArtifactoryTrackingServiceTest {

    private static final String BUILD_CONTENT_ID = "build-12345";
    private static final String PROJECT = "pnc";
    private static final String ARTIFACTORY_URL = "http://artifactory-host";

    private InternalArtifactoryTrackingService service;
    private Artifactory artifactory;
    private Searches searches;
    private Configuration configuration;

    /** Collects JUL records published through the service's JBoss-backed SLF4J logger. */
    private CapturingHandler logCapture;
    private Logger julLogger;

    @BeforeEach
    void setUp() {
        service = new InternalArtifactoryTrackingService();

        searches = mock(Searches.class);
        artifactory = mock(Artifactory.class, RETURNS_DEEP_STUBS);
        configuration = mock(Configuration.class);

        when(artifactory.searches()).thenReturn(searches);
        when(configuration.getArtifactoryProject()).thenReturn(PROJECT);
        when(configuration.getArtifactoryUrl()).thenReturn(ARTIFACTORY_URL);

        service.artifactory = artifactory;
        service.configuration = configuration;
        service.useInternalTracking = true;

        // slf4j-jboss-logmanager calls LogContext.getLogContext().getLogger(name) — not the
        // vanilla JUL LogManager — so we must use the same LogContext to get the same instance.
        logCapture = new CapturingHandler();
        julLogger = LogContext.getLogContext().getLogger(InternalArtifactoryTrackingService.class.getName());
        julLogger.addHandler(logCapture);
        julLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        julLogger.removeHandler(logCapture);
    }

    // -------------------------------------------------------------------------
    // getReport – happy path
    // -------------------------------------------------------------------------

    @Test
    void getReport_returnsReportWithUploadsAndDownloads() {
        // Artifact in build repo → upload
        AqlItem upload = aqlItem(
                PROJECT + "-mvn-build-" + BUILD_CONTENT_ID,
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256up",
                "sha1up",
                "md5up",
                1024L,
                null);
        // Artifact NOT in build repo → download
        AqlItem download = aqlItem(
                PROJECT + "-mvn-shared-imports",
                "/org/example/dep/1.0",
                "dep-1.0.jar",
                "sha256dn",
                "sha1dn",
                "md5dn",
                2048L,
                null);

        stubAqlSearch(List.of(upload, download));

        TrackingReport report = service.getReport(BUILD_CONTENT_ID);

        assertNotNull(report);
        assertEquals(BUILD_CONTENT_ID, report.getTrackingID());
        assertEquals(1, report.getUploads().size());
        assertEquals(1, report.getDownloads().size());

        TrackedEntry uploadEntry = report.getUploads().iterator().next();
        // TrackedArtifact.getPath() always normalises by prepending '/' when absent
        assertEquals("/org/example/lib/1.0/lib-1.0.jar", uploadEntry.getPath());
        assertEquals("sha256up", uploadEntry.getSha256());
        assertEquals("sha1up", uploadEntry.getSha1());
        assertEquals("md5up", uploadEntry.getMd5());
        assertEquals(1024L, uploadEntry.getSize());

        TrackedEntry downloadEntry = report.getDownloads().iterator().next();
        assertEquals("/org/example/dep/1.0/dep-1.0.jar", downloadEntry.getPath());
        assertEquals("sha256dn", downloadEntry.getSha256());
        assertEquals(2048L, downloadEntry.getSize());
    }

    @Test
    void getReport_logsErrorWhenNoUploadsFound() {
        // All results are in a repo that does NOT contain the build content ID → all downloads
        AqlItem downloadOnly = aqlItem(
                PROJECT + "-mvn-shared-imports",
                "org/example/dep/1.0",
                "dep-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                512L,
                null);

        stubAqlSearch(List.of(downloadOnly));

        TrackingReport report = service.getReport(BUILD_CONTENT_ID);

        assertNotNull(report);
        assertTrue(report.getUploads().isEmpty());
        assertTrue(
                logCapture.errorMessages().stream().anyMatch(m -> m.contains("No uploads found")),
                "Expected an ERROR log message containing 'No uploads found'");
    }

    @Test
    void getReport_throwsWhenInternalTrackingDisabled() {
        service.useInternalTracking = false;

        assertThrows(UnsupportedOperationException.class, () -> service.getReport(BUILD_CONTENT_ID));
        verify(artifactory, never()).searches();
    }

    @Test
    void getReport_emptyResultsLogsError() {
        stubAqlSearch(List.of());

        TrackingReport report = service.getReport(BUILD_CONTENT_ID);

        assertNotNull(report);
        assertTrue(report.getUploads().isEmpty());
        assertTrue(report.getDownloads().isEmpty());
        assertTrue(
                logCapture.errorMessages().stream().anyMatch(m -> m.contains("No uploads found")),
                "Expected an ERROR log message containing 'No uploads found'");
    }

    @Test
    void getReport_issuesExactlyOneArtifactoryCall() {
        AqlItem upload = aqlItem(
                PROJECT + "-mvn-build-" + BUILD_CONTENT_ID,
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                100L,
                null);

        stubAqlSearch(List.of(upload));
        service.getReport(BUILD_CONTENT_ID);

        // The only Artifactory interaction must be the single artifactsByFileSpec call
        verify(searches).artifactsByFileSpec(any(FileSpec.class));
        // No per-artifact repository() calls
        verify(artifactory, never()).repository(any());
    }

    // -------------------------------------------------------------------------
    // convertAqlItemToTrackedEntry
    // -------------------------------------------------------------------------

    @Test
    void convertAqlItemToTrackedEntry_stripsProjectPrefix() {
        AqlItem item = aqlItem(
                PROJECT + "-mvn-build-12345",
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                512L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.MAVEN);

        assertEquals("mvn-build-12345", entry.getRepoId().getName());
        assertEquals(PROJECT, entry.getRepoId().getProject());
        assertEquals(PackageType.MAVEN, entry.getRepoId().getPackageType());
    }

    @Test
    void convertAqlItemToTrackedEntry_keepsFullRepoKeyWhenNoProjectPrefix() {
        AqlItem item = aqlItem(
                "external-central",
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                256L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.MAVEN);

        assertEquals("external-central", entry.getRepoId().getName());
    }

    @Test
    void convertAqlItemToTrackedEntry_joinsDirectoryAndName() {
        AqlItem item = aqlItem(
                PROJECT + "-mvn-build-99",
                "/some/dir",
                "artifact.tgz",
                null,
                null,
                null,
                0L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.GENERIC);

        // TrackedArtifact.getPath() always normalises by prepending '/'
        assertEquals("/some/dir/artifact.tgz", entry.getPath());
    }

    @Test
    void convertAqlItemToTrackedEntry_handlesRootArtifactDot() {
        // When AQL returns path = "." the artifact is at the repo root
        AqlItem item = aqlItem(
                PROJECT + "-generic-build-1",
                ".",
                "rootfile.tar.gz",
                null,
                null,
                null,
                0L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.GENERIC);

        // TrackedArtifact.getPath() always normalises by prepending '/'
        assertEquals("/rootfile.tar.gz", entry.getPath());
    }

    @Test
    void convertAqlItemToTrackedEntry_buildsLocalUrlCorrectly() {
        String repoKey = PROJECT + "-generic-build-99";
        AqlItem item = aqlItem(repoKey, "/some", "artifact.tgz", null, null, null, 0L, null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.GENERIC);

        assertEquals(ARTIFACTORY_URL + "/" + repoKey + "/some/artifact.tgz", entry.getLocalUrl());
    }

    @Test
    void convertAqlItemToTrackedEntry_usesOriginUrlFromPropertyWhenAvailable() {
        String originUrl = "https://repo1.maven.org/maven2/org/example/lib/1.0/lib-1.0.jar";
        AqlItem item = aqlItem(
                PROJECT + "-mvn-shared-imports",
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                300L,
                originUrl);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.MAVEN);

        assertEquals(originUrl, entry.getOriginUrl());
    }

    @Test
    void convertAqlItemToTrackedEntry_fallsBackToLocalUrlWhenOriginPropertyMissing() {
        String repoKey = PROJECT + "-mvn-shared-imports";
        AqlItem item = aqlItem(
                repoKey,
                "/org/example/lib/1.0",
                "lib-1.0.jar",
                "sha256",
                "sha1",
                "md5",
                300L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.MAVEN);

        assertEquals(
                ARTIFACTORY_URL + "/" + repoKey + "/org/example/lib/1.0/lib-1.0.jar",
                entry.getOriginUrl());
    }

    @Test
    void convertAqlItemToTrackedEntry_mapsAllChecksums() {
        AqlItem item = aqlItem(
                PROJECT + "-mvn-build-1",
                "/a/b",
                "c.jar",
                "mysha256",
                "mysha1",
                "mymd5",
                999L,
                null);

        TrackedEntry entry = service.convertAqlItemToTrackedEntry(item, PackageType.MAVEN);

        assertEquals("mysha256", entry.getSha256());
        assertEquals("mysha1", entry.getSha1());
        assertEquals("mymd5", entry.getMd5());
        assertEquals(999L, entry.getSize());
    }

    // -------------------------------------------------------------------------
    // detectPackageType — driven through convertAqlItemToTrackedEntry
    // -------------------------------------------------------------------------

    @Test
    void detectPackageType_maven_fromMvnSubstring() {
        assertPackageType("pnc-mvn-build-123", PackageType.MAVEN);
    }

    @Test
    void detectPackageType_maven_fromMavenSubstring() {
        assertPackageType("pnc-maven-central", PackageType.MAVEN);
    }

    @Test
    void detectPackageType_npm_fromNpmSubstring() {
        assertPackageType("pnc-npm-build-123", PackageType.NPM);
    }

    @Test
    void detectPackageType_npm_fromNpmjsSubstring() {
        assertPackageType("pnc-npmjs-proxy", PackageType.NPM);
    }

    @Test
    void detectPackageType_npm_fromYarnpkgSubstring() {
        assertPackageType("pnc-yarnpkg-proxy", PackageType.NPM);
    }

    @Test
    void detectPackageType_generic_whenNoKnownType() {
        assertPackageType("pnc-generic-build-123", PackageType.GENERIC);
    }

    // -------------------------------------------------------------------------
    // resolveRepoPackageTypes — heuristic + API fallback
    // -------------------------------------------------------------------------

    @Test
    void resolveRepoPackageTypes_mavenByName_noApiCall() {
        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-mvn-build-123"));

        assertEquals(PackageType.MAVEN, result.get("pnc-mvn-build-123"));
        verify(artifactory, never()).repository(any());
    }

    @Test
    void resolveRepoPackageTypes_npmByName_noApiCall() {
        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-npm-build-123"));

        assertEquals(PackageType.NPM, result.get("pnc-npm-build-123"));
        verify(artifactory, never()).repository(any());
    }

    @Test
    void resolveRepoPackageTypes_unknownName_fallsBackToApiMaven() {
        stubRepoPackageType("pnc-custom-proxy", PackageTypeImpl.maven);

        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-custom-proxy"));

        assertEquals(PackageType.MAVEN, result.get("pnc-custom-proxy"));
        verify(artifactory, times(1)).repository("pnc-custom-proxy");
    }

    @Test
    void resolveRepoPackageTypes_unknownName_fallsBackToApiNpm() {
        stubRepoPackageType("pnc-custom-proxy", PackageTypeImpl.npm);

        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-custom-proxy"));

        assertEquals(PackageType.NPM, result.get("pnc-custom-proxy"));
    }

    @Test
    void resolveRepoPackageTypes_unknownName_fallsBackToApiGeneric() {
        stubRepoPackageType("pnc-custom-proxy", PackageTypeImpl.generic);

        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-custom-proxy"));

        assertEquals(PackageType.GENERIC, result.get("pnc-custom-proxy"));
    }

    @Test
    void resolveRepoPackageTypes_mixedSet_onlyUnknownNamesCallApi() {
        String gradleRepo = "pnc-devel-repo-gradle-org-f31137e9bffc";
        stubRepoPackageType(gradleRepo, PackageTypeImpl.gradle);

        Map<String, PackageType> result = service.resolveRepoPackageTypes(
                Set.of("pnc-mvn-build-123", "pnc-npm-build-123", gradleRepo));

        assertEquals(PackageType.MAVEN, result.get("pnc-mvn-build-123"));
        assertEquals(PackageType.NPM, result.get("pnc-npm-build-123"));
        assertEquals(PackageType.MAVEN, result.get(gradleRepo));
        // Only the unrecognised repo triggered an API call
        verify(artifactory, times(1)).repository(any());
        verify(artifactory, times(1)).repository(gradleRepo);
    }

    @Test
    void resolveRepoPackageTypes_apiThrowsException_defaultsToGenericAndLogsWarning() {
        RepositoryHandle handle = mock(RepositoryHandle.class);
        when(artifactory.repository("pnc-unknown-repo")).thenReturn(handle);
        when(handle.get()).thenThrow(new RuntimeException("connection refused"));

        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of("pnc-unknown-repo"));

        assertEquals(PackageType.GENERIC, result.get("pnc-unknown-repo"));
        assertTrue(logCapture.warningMessages().stream().anyMatch(m -> m.contains("pnc-unknown-repo")));
    }

    @Test
    void resolveRepoPackageTypes_emptySet_returnsEmptyMap() {
        Map<String, PackageType> result = service.resolveRepoPackageTypes(Set.of());

        assertTrue(result.isEmpty());
        verify(artifactory, never()).repository(any());
    }

    // -------------------------------------------------------------------------
    // No-op / stub interface methods
    // -------------------------------------------------------------------------

    @Test
    void initReport_isNoOp() {
        service.initReport("build-1");
        verify(artifactory, never()).searches();
    }

    @Test
    void sealReport_isNoOp() {
        service.sealReport("build-1");
        verify(artifactory, never()).searches();
    }

    @Test
    void clearReport_isNoOp() {
        service.clearReport("build-1");
        verify(artifactory, never()).searches();
    }

    @Test
    void getAllIds_returnsEmptyList() {
        List<String> ids = service.getAllIds("*");
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void getUploadPaths_returnsEmptyList() {
        List<String> paths = service.getUploadPaths("build-1");
        assertNotNull(paths);
        assertTrue(paths.isEmpty());
    }

    @Test
    void trackDownload_isNoOp() {
        service.trackDownload("build-1", null);
        verify(artifactory, never()).searches();
    }

    @Test
    void trackUpload_isNoOp() {
        service.trackUpload("build-1", null);
        verify(artifactory, never()).searches();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Stubs {@code searches.artifactsByFileSpec(any)} to return the given items.
     * This is the only Artifactory call made after the refactoring.
     */
    private void stubAqlSearch(List<AqlItem> results) {
        when(searches.artifactsByFileSpec(any(FileSpec.class))).thenReturn(results);
    }

    /**
     * Builds a fully-populated mock {@link AqlItem}.
     *
     * @param originUrl when non-null, adds a {@code jf.origin.remote.path} property
     */
    private static AqlItem aqlItem(
            String repo,
            String path,
            String name,
            String sha256,
            String sha1,
            String md5,
            long size,
            String originUrl) {

        AqlItem item = mock(AqlItem.class);
        when(item.getRepo()).thenReturn(repo);
        when(item.getPath()).thenReturn(path);
        when(item.getName()).thenReturn(name);
        when(item.getSha256()).thenReturn(sha256);
        when(item.getActualSha1()).thenReturn(sha1);
        when(item.getActualMd5()).thenReturn(md5);
        when(item.getSize()).thenReturn(size);

        if (originUrl != null) {
            AqlItem.Property prop = mock(AqlItem.Property.class);
            when(prop.getkey()).thenReturn("jf.origin.remote.path");
            when(prop.getValue()).thenReturn(originUrl);
            when(item.getProperties()).thenReturn(List.of(prop));
        } else {
            when(item.getProperties()).thenReturn(List.of());
        }

        return item;
    }

    /**
     * Asserts that the given repoKey produces the expected {@link PackageType}.
     * Drives {@code detectPackageType} (private) through {@code getReport} so that
     * the package-type classification is observed on the resulting {@link TrackedEntry}.
     */
    private void assertPackageType(String repoKey, PackageType expected) {
        // Use a buildContentId that appears inside the repoKey so the item is an upload
        String buildId = "fake-build";
        String uploadRepoKey = repoKey.contains(buildId) ? repoKey : repoKey + "-" + buildId;

        AqlItem item = aqlItem(uploadRepoKey, "/x", "y.jar", "sha256", "sha1", "md5", 1L, null);
        stubAqlSearch(List.of(item));

        TrackingReport report = service.getReport(buildId);
        TrackedEntry entry = report.getUploads().iterator().next();
        assertEquals(expected, entry.getRepoId().getPackageType());
    }

    /**
     * A JUL {@link Handler} that collects every published {@link LogRecord} so tests can
     * assert on log output without requiring any additional logging library.
     *
     * <p>
     * The SLF4J binding in this project ({@code slf4j-jboss-logmanager}) routes every
     * SLF4J call to the JUL logger with the same name as the SLF4J logger, making this a
     * zero-dependency, zero-configuration way to observe log output in plain JUnit tests.
     */
    private static class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            records.clear();
        }

        /** Returns the formatted messages of all records at ERROR/SEVERE level (intValue == 1000). */
        List<String> errorMessages() {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() == Level.SEVERE.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }

        /** Returns the formatted messages of all records at WARN level (intValue == 900). */
        List<String> warningMessages() {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() == Level.WARNING.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    /**
     * Stubs {@code artifactory.repository(repoKey).get().getRepositorySettings().getPackageType()}
     * to return the given JFrog {@link PackageTypeImpl}.
     */
    private void stubRepoPackageType(String repoKey, PackageTypeImpl packageType) {
        RepositorySettings settings = mock(RepositorySettings.class);
        when(settings.getPackageType()).thenReturn(packageType);
        Repository repo = mock(Repository.class);
        when(repo.getRepositorySettings()).thenReturn(settings);
        RepositoryHandle handle = mock(RepositoryHandle.class);
        when(handle.get()).thenReturn(repo);
        when(artifactory.repository(repoKey)).thenReturn(handle);
    }
}
