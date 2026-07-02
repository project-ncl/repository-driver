/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.repositorydriver.buildinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashSet;
import java.util.Set;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.Module;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BuildInfoConverter.
 *
 * @author <a href="mailto:ncross@redhat.com">Nick Cross</a>
 */
public class BuildInfoConverterTest {

    @Test
    public void testConvertTrackingReportWithUploadsAndDownloads() {
        // Given
        Set<TrackedEntry> uploads = new HashSet<>();
        RepositoryId uploadRepoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("build-repo")
                .build();
        TrackedEntry upload1 = TrackedEntry.builder()
                .repoId(uploadRepoId)
                .path("/org/example/myapp/1.0/myapp-1.0.jar")
                .sha256("abc123")
                .sha1("def456")
                .md5("ghi789")
                .localUrl("file:///tmp/myapp-1.0.jar")
                .build();
        uploads.add(upload1);

        TrackedEntry upload2 = TrackedEntry.builder()
                .repoId(uploadRepoId)
                .path("/org/example/myapp/1.0/myapp-1.0.pom")
                .sha256("xyz123")
                .sha1("uvw456")
                .md5("rst789")
                .localUrl("file:///tmp/myapp-1.0.pom")
                .build();
        uploads.add(upload2);

        Set<TrackedEntry> downloads = new HashSet<>();
        RepositoryId repoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("central")
                .build();
        TrackedEntry download1 = TrackedEntry.builder()
                .path("/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar")
                .repoId(repoId)
                .sha256("dep123")
                .sha1("dep456")
                .md5("dep789")
                .originUrl(
                        "https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar")
                .build();
        downloads.add(download1);

        TrackingReport report = TrackingReport.builder()
                .trackingID("build-123")
                .uploads(uploads)
                .downloads(downloads)
                .build();

        // When
        Build build = BuildInfoConverter.fromTrackingReport(report, "pnc", "test-build");

        // Then
        assertNotNull(build);
        assertEquals("test-build", build.getName());
        assertEquals("build-123", build.getNumber());
        assertEquals("pnc", build.getProject());
        assertEquals("1.0.1", build.getVersion());
        assertNotNull(build.getStarted());
        assertNotNull(build.getBuildAgent());
        assertEquals("NYI", build.getBuildAgent().getName());

        assertNotNull(build.getModules());
        assertEquals(1, build.getModules().size());

        Module module = build.getModules().get(0);
        assertEquals("test-build:build-123", module.getId());
        assertEquals("maven", module.getType());

        // Verify artifacts
        assertNotNull(module.getArtifacts());
        assertEquals(2, module.getArtifacts().size());
        Artifact artifact1 = module.getArtifacts()
                .stream()
                .filter(a -> a.getName().equals("myapp-1.0.jar"))
                .findFirst()
                .orElse(null);
        assertNotNull(artifact1);
        assertEquals("jar", artifact1.getType());
        assertEquals("abc123", artifact1.getSha256());
        assertEquals("def456", artifact1.getSha1());
        assertEquals("ghi789", artifact1.getMd5());
        assertEquals("/org/example/myapp/1.0/myapp-1.0.jar", artifact1.getRemotePath());

        // Verify dependencies
        assertNotNull(module.getDependencies());
        assertEquals(1, module.getDependencies().size());
        Dependency dependency = module.getDependencies().get(0);
        assertEquals("jar", dependency.getType());
        assertEquals("/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar", dependency.getId());
        assertEquals("dep123", dependency.getSha256());
        assertEquals("dep456", dependency.getSha1());
        assertEquals("dep789", dependency.getMd5());
    }

    @Test
    public void testConvertTrackingReportWithOnlyUploads() {
        // Given
        Set<TrackedEntry> uploads = new HashSet<>();
        RepositoryId uploadRepoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.NPM)
                .name("npm-build-repo")
                .build();
        TrackedEntry upload = TrackedEntry.builder()
                .repoId(uploadRepoId)
                .path("/my-package/1.0.0/my-package-1.0.0.tgz")
                .sha256("npm123")
                .build();
        uploads.add(upload);

        TrackingReport report = TrackingReport.builder()
                .trackingID("npm-build-456")
                .uploads(uploads)
                .downloads(new HashSet<>())
                .build();

        // When
        Build build = BuildInfoConverter.fromTrackingReport(report, "pnc", "npm-build");

        // Then
        assertNotNull(build);
        assertEquals("npm-build", build.getName());
        assertEquals("npm-build-456", build.getNumber());
        assertEquals("pnc", build.getProject());
        Module module = build.getModules().get(0);
        assertEquals(1, module.getArtifacts().size());
        assertEquals(0, module.getDependencies().size());
    }

    @Test
    public void testConvertTrackingReportWithOnlyDownloads() {
        // Given
        Set<TrackedEntry> downloads = new HashSet<>();
        RepositoryId repoId = RepositoryId.builder()
                .project("pnc")
                .packageType(PackageType.MAVEN)
                .name("maven-central")
                .build();
        TrackedEntry download = TrackedEntry.builder()
                .path("/junit/junit/4.13.2/junit-4.13.2.jar")
                .repoId(repoId)
                .sha256("test123")
                .build();
        downloads.add(download);

        TrackingReport report = TrackingReport.builder()
                .trackingID("test-789")
                .uploads(new HashSet<>())
                .downloads(downloads)
                .build();

        // When
        Build build = BuildInfoConverter.fromTrackingReport(report, "pnc", "test-build");

        // Then
        assertNotNull(build);
        Module module = build.getModules().get(0);
        assertEquals(0, module.getArtifacts().size());
        assertEquals(1, module.getDependencies().size());
    }

    @Test
    public void testConvertEmptyTrackingReport() {
        // Given
        TrackingReport report = TrackingReport.builder()
                .trackingID("empty-000")
                .uploads(new HashSet<>())
                .downloads(new HashSet<>())
                .build();

        // When
        Build build = BuildInfoConverter.fromTrackingReport(report, "pnc", "empty-build");

        // Then
        assertNotNull(build);
        assertEquals("empty-build", build.getName());
        assertEquals("empty-000", build.getNumber());
        assertNotNull(build.getModules());
        assertEquals(1, build.getModules().size());
        Module module = build.getModules().get(0);
        assertNotNull(module.getArtifacts());
        assertEquals(0, module.getArtifacts().size());
        assertNotNull(module.getDependencies());
        assertEquals(0, module.getDependencies().size());
    }
}

// Made with Bob
