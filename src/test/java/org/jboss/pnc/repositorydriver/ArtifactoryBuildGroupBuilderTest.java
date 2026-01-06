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

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
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
                .addGlobalConstituents(BuildType.MVN, false)
                .addExtraConstituents(
                        Collections.singletonList("https://repo1.maven.org/maven2/"))
                .build();

        assertEquals(3, result.getRepositories().size());
        assertEquals(configuration.getDeploymentType() + "-virtual-ID", result.getKey());
        assertInstanceOf(MavenRepositorySettings.class, result.getRepositorySettings());
        assertTrue(((MavenRepositorySettings) result.getRepositorySettings()).getHandleReleases());
        assertFalse(((MavenRepositorySettings) result.getRepositorySettings()).getHandleSnapshots());
    }
}
