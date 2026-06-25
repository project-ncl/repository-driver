package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.Collections;

import jakarta.inject.Inject;

import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.repositorydriver.group.ArtifactoryBuildGroupBuilder;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.Repositories;
import org.jfrog.artifactory.client.RepositoryHandle;
import org.jfrog.artifactory.client.model.RemoteRepository;
import org.jfrog.artifactory.client.model.builder.RemoteRepositoryBuilder;
import org.jfrog.artifactory.client.model.builder.RepositoryBuilders;
import org.jfrog.artifactory.client.model.impl.VirtualRepositoryBuilderImpl;
import org.jfrog.artifactory.client.model.repository.settings.MavenRepositorySettings;
import org.jfrog.artifactory.client.model.repository.settings.impl.MavenRepositorySettingsImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ArtifactoryBuildGroupBuilderTest {

    @Inject
    Configuration configuration;

    @Test
    public void shouldBuildVirtualRepositoryWithConstituents() {

        // Mock RemoteRepositoryBuilder
        RemoteRepositoryBuilder remoteRepositoryBuilder = Mockito.mock(RemoteRepositoryBuilder.class);
        Mockito.when(remoteRepositoryBuilder.archiveBrowsingEnabled(any(Boolean.class)))
                .thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.description(any())).thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.repositorySettings(any())).thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.url(anyString())).thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.key(anyString())).thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.environments(any())).thenReturn(remoteRepositoryBuilder);
        Mockito.when(remoteRepositoryBuilder.projectKey(any())).thenReturn(remoteRepositoryBuilder);

        //        // Mock VirtualRepositoryBuilder
        //        VirtualRepositoryBuilder virtualRepositoryBuilder = Mockito.mock(VirtualRepositoryBuilder.class);
        //        Mockito.when(virtualRepositoryBuilder.repositorySettings(any())).thenReturn(virtualRepositoryBuilder);
        //        Mockito.when(virtualRepositoryBuilder.description(any())).thenReturn(virtualRepositoryBuilder);
        //        Mockito.when(virtualRepositoryBuilder.repositories(any(Collection.class))).thenReturn(virtualRepositoryBuilder);
        //        Mockito.when(virtualRepositoryBuilder.key(anyString())).thenReturn(virtualRepositoryBuilder);

        // Mock RepositoryBuilders
        RepositoryBuilders repositoryBuilders = Mockito.mock(RepositoryBuilders.class);
        Mockito.when(repositoryBuilders.remoteRepositoryBuilder()).thenReturn(remoteRepositoryBuilder);
        Mockito.when(repositoryBuilders.virtualRepositoryBuilder()).thenReturn(new VirtualRepositoryBuilderImpl() {
        });

        // Mock Repositories
        Repositories repositories = Mockito.mock(Repositories.class);
        Mockito.when(repositories.builders()).thenReturn(repositoryBuilders);
        Mockito.when(repositories.create(anyInt(), any(RemoteRepository.class))).thenReturn("CREATED");

        // Mock Repository
        RepositoryHandle repository = Mockito.mock(RepositoryHandle.class);
        Mockito.when(repository.exists()).thenReturn(false);

        Artifactory artifactory = Mockito.mock(Artifactory.class);
        Mockito.when(artifactory.repositories()).thenReturn(repositories);
        Mockito.when(artifactory.repository(anyString())).thenReturn(repository);

        MavenRepositorySettingsImpl settings = new MavenRepositorySettingsImpl();
        settings.setHandleReleases(true);
        settings.setHandleSnapshots(false);
        var result = ArtifactoryBuildGroupBuilder.builder(configuration, artifactory, settings, "pnc-virtual-ID")
                .addConstituent("TEST")
                .addGlobalConstituents(BuildType.MVN, BuildCategory.STANDARD, false)
                .addExtraConstituents(
                        Collections.singletonList("https://repo1.maven.org/maven2/"))
                .build();

        System.out.println("### Got repositories " + result.getRepositories());
        // Expected: TEST + pnc-central + pnc-builds-imports-public + repo1-maven-org-<hash> = 4 repos
        assertEquals(4, result.getRepositories().size());
        assertEquals(configuration.getDeploymentType() + "-virtual-ID", result.getKey());
        assertInstanceOf(MavenRepositorySettings.class, result.getRepositorySettings());
        assertTrue(((MavenRepositorySettings) result.getRepositorySettings()).getHandleReleases());
        assertFalse(((MavenRepositorySettings) result.getRepositorySettings()).getHandleSnapshots());
    }

    @Test
    public void shouldAddGlobalConstituentsForNonTempBuild() {
        // Setup mocks
        Artifactory artifactory = Mockito.mock(Artifactory.class);
        RepositoryBuilders repositoryBuilders = Mockito.mock(RepositoryBuilders.class);
        Repositories repositories = Mockito.mock(Repositories.class);
        Mockito.when(artifactory.repositories()).thenReturn(repositories);
        Mockito.when(repositories.builders()).thenReturn(repositoryBuilders);
        Mockito.when(repositoryBuilders.virtualRepositoryBuilder()).thenReturn(new VirtualRepositoryBuilderImpl() {
        });

        MavenRepositorySettingsImpl settings = new MavenRepositorySettingsImpl();

        // Build with non-temp global constituents
        var result = ArtifactoryBuildGroupBuilder.builder(configuration, artifactory, settings, "test-virtual")
                .addGlobalConstituents(BuildType.MVN, BuildCategory.STANDARD, false)
                .build();

        // standard.build-group-constituents.hosted: [central]
        // standard.build-group-constituents.group: not defined, falls back to default from main config
        // Constituent repos use simple project-prefix naming: pnc-<constituent-name>
        var repos = result.getRepositories();
        System.out.println("### Non-temp repositories: " + repos);

        // Expected: pnc-central (hosted) + pnc-builds-imports-public (group from main config default)
        assertEquals(2, repos.size(), "Should have 2 constituents for non-temp build");
        assertTrue(repos.contains("pnc-central"), "Should contain pnc-central");
        assertTrue(
                repos.contains("pnc-builds-imports-public"),
                "Should contain pnc-builds-imports-public");
    }

    @Test
    public void shouldAddGlobalConstituentsForTempBuild() {
        // Setup mocks
        Artifactory artifactory = Mockito.mock(Artifactory.class);
        RepositoryBuilders repositoryBuilders = Mockito.mock(RepositoryBuilders.class);
        Repositories repositories = Mockito.mock(Repositories.class);
        Mockito.when(artifactory.repositories()).thenReturn(repositories);
        Mockito.when(repositories.builders()).thenReturn(repositoryBuilders);
        Mockito.when(repositoryBuilders.virtualRepositoryBuilder()).thenReturn(new VirtualRepositoryBuilderImpl() {
        });

        MavenRepositorySettingsImpl settings = new MavenRepositorySettingsImpl();

        // Build with temp global constituents
        var result = ArtifactoryBuildGroupBuilder.builder(configuration, artifactory, settings, "test-virtual")
                .addGlobalConstituents(BuildType.MVN, BuildCategory.STANDARD, true)
                .build();

        // standard.build-group-constituents.temp-hosted: [temp-central]
        // standard.build-group-constituents.temp-group: [] (empty)
        // Constituent repos use simple project-prefix naming: pnc-<constituent-name>
        var repos = result.getRepositories();
        System.out.println("### Temp repositories: " + repos);

        // Expected: pnc-temp-central (temp-hosted only, temp-group is empty)
        assertEquals(1, repos.size(), "Should have 1 constituent for temp build");
        assertTrue(repos.contains("pnc-temp-central"), "Should contain pnc-temp-central");
    }

    @Test
    public void shouldAddGlobalConstituentsForGradleBuild() {
        // Setup mocks
        Artifactory artifactory = Mockito.mock(Artifactory.class);
        RepositoryBuilders repositoryBuilders = Mockito.mock(RepositoryBuilders.class);
        Repositories repositories = Mockito.mock(Repositories.class);
        Mockito.when(artifactory.repositories()).thenReturn(repositories);
        Mockito.when(repositories.builders()).thenReturn(repositoryBuilders);
        Mockito.when(repositoryBuilders.virtualRepositoryBuilder()).thenReturn(new VirtualRepositoryBuilderImpl() {
        });

        MavenRepositorySettingsImpl settings = new MavenRepositorySettingsImpl();

        // Build with Gradle type (should add gradle-plugins repo)
        var result = ArtifactoryBuildGroupBuilder.builder(configuration, artifactory, settings, "test-virtual")
                .addGlobalConstituents(BuildType.GRADLE, BuildCategory.STANDARD, false)
                .build();

        var repos = result.getRepositories();
        System.out.println("### Gradle repositories: " + repos);

        // Should include gradle-plugins repo in addition to standard constituents
        // standard.hosted: [central], standard.group: falls back to main config default
        // Constituent repos use simple project-prefix naming: pnc-<constituent-name>
        assertEquals(3, repos.size(), "Should have 3 constituents for Gradle build");
        assertTrue(repos.contains("pnc-central"), "Should contain pnc-central");
        assertTrue(
                repos.contains("pnc-gradle-plugins"),
                "Should contain pnc-gradle-plugins repository for Gradle builds");
        assertTrue(
                repos.contains("pnc-builds-imports-public"),
                "Should contain pnc-builds-imports-public");
    }

    @Test
    public void shouldAddGlobalConstituentsForGradleTempBuild() {
        // Setup mocks
        Artifactory artifactory = Mockito.mock(Artifactory.class);
        RepositoryBuilders repositoryBuilders = Mockito.mock(RepositoryBuilders.class);
        Repositories repositories = Mockito.mock(Repositories.class);
        Mockito.when(artifactory.repositories()).thenReturn(repositories);
        Mockito.when(repositories.builders()).thenReturn(repositoryBuilders);
        Mockito.when(repositoryBuilders.virtualRepositoryBuilder()).thenReturn(new VirtualRepositoryBuilderImpl() {
        });

        MavenRepositorySettingsImpl settings = new MavenRepositorySettingsImpl();

        // Build with Gradle type temp build (should add gradle-plugins repo)
        var result = ArtifactoryBuildGroupBuilder.builder(configuration, artifactory, settings, "test-virtual")
                .addGlobalConstituents(BuildType.GRADLE, BuildCategory.STANDARD, true)
                .build();

        var repos = result.getRepositories();
        System.out.println("### Gradle temp repositories: " + repos);

        // Should include gradle-plugins repo (same name for temp and non-temp)
        // standard.temp-hosted: [temp-central], standard.temp-group: [] (empty)
        // Constituent repos use simple project-prefix naming: pnc-<constituent-name>
        assertEquals(2, repos.size(), "Should have 2 constituents for Gradle temp build");
        assertTrue(repos.contains("pnc-temp-central"), "Should contain pnc-temp-central");
        assertTrue(
                repos.contains("pnc-gradle-plugins"),
                "Should contain pnc-gradle-plugins repository for Gradle temp builds");
    }
}
