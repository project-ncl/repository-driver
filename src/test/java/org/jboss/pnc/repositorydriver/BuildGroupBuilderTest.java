package org.jboss.pnc.repositorydriver;

import java.util.ArrayList;
import java.util.List;

import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.module.IndyStoresClientModule;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class BuildGroupBuilderTest {

    @Test
    public void shouldAddExtraRepositoryToBuildGroup() throws IndyClientException {

        IndyStoresClientModule indyStore = Mockito.mock(IndyStoresClientModule.class);
        Mockito.when(indyStore.exists(any())).thenReturn(false);
        StoreListingDTO<RemoteRepository> storeListing = new StoreListingDTO<>();
        Mockito.when(indyStore.listRemoteRepositories(any())).thenReturn(storeListing);
        Mockito.when(indyStore.create(any(), anyString(), any())).thenReturn(null);

        Indy indy = Mockito.mock(Indy.class);
        Mockito.when(indy.stores()).thenReturn(indyStore);

        List<String> repositories = new ArrayList<>();
        repositories.add("http://test.com/maven");
        repositories.add("invalid url"); // should not be added
        Group buildGroup = BuildGroupBuilder.builder(indy, MavenPackageTypeDescriptor.MAVEN_PKG_KEY, "build-X")
                .addExtraConstituents(repositories)
                .build();

        Assertions.assertEquals(1, buildGroup.getConstituents().size());
        StoreKey storeKey = buildGroup.getConstituents().stream().findAny().get();
        Assertions.assertEquals("i-test-com", storeKey.getName());
    }
}
