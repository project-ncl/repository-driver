package org.jboss.pnc.repositorydriver;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.indy.client.core.module.IndyStoresClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.util.jhttpc.auth.PasswordManager;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class IndyMock extends Indy {

    public IndyMock(String baseUrl, IndyClientModule... modules) throws IndyClientException {
        super(baseUrl, modules);
    }

    public IndyMock(String baseUrl, IndyClientAuthenticator authenticator, IndyClientModule... modules)
            throws IndyClientException {
        super(baseUrl, authenticator, modules);
    }

    public IndyMock(String baseUrl, IndyObjectMapper mapper, IndyClientModule... modules) throws IndyClientException {
        super(baseUrl, mapper, modules);
    }

    public IndyMock(
            String baseUrl,
            IndyClientAuthenticator authenticator,
            IndyObjectMapper mapper,
            IndyClientModule... modules) throws IndyClientException {
        super(baseUrl, authenticator, mapper, modules);
    }

    public IndyMock(String baseUrl, Collection<IndyClientModule> modules) throws IndyClientException {
        super(baseUrl, modules);
    }

    public IndyMock(String baseUrl, IndyClientAuthenticator authenticator, Collection<IndyClientModule> modules)
            throws IndyClientException {
        super(baseUrl, authenticator, modules);
    }

    public IndyMock(String baseUrl, IndyObjectMapper mapper, Collection<IndyClientModule> modules)
            throws IndyClientException {
        super(baseUrl, mapper, modules);
    }

    public IndyMock(
            String baseUrl,
            IndyClientAuthenticator authenticator,
            IndyObjectMapper mapper,
            Collection<IndyClientModule> modules) throws IndyClientException {
        super(baseUrl, authenticator, mapper, modules);
    }

    public IndyMock(
            IndyClientAuthenticator authenticator,
            IndyObjectMapper mapper,
            Collection<IndyClientModule> modules,
            SiteConfig location) throws IndyClientException {
        super(authenticator, mapper, modules, location);
    }

    public IndyMock(
            SiteConfig location,
            IndyClientAuthenticator authenticator,
            IndyObjectMapper mapper,
            IndyClientModule... modules) throws IndyClientException {
        super(location, authenticator, mapper, modules);
    }

    public IndyMock(
            SiteConfig location,
            IndyClientAuthenticator authenticator,
            IndyObjectMapper mapper,
            Map<String, String> mdcCopyMappings,
            IndyClientModule... modules) throws IndyClientException {
        super(location, authenticator, mapper, mdcCopyMappings, modules);
    }

    public IndyMock(SiteConfig location, PasswordManager passwordManager, IndyClientModule... modules)
            throws IndyClientException {
        super(location, passwordManager, modules);
    }

    public IndyMock(
            SiteConfig location,
            PasswordManager passwordManager,
            IndyObjectMapper objectMapper,
            IndyClientModule... modules) throws IndyClientException {
        super(location, passwordManager, objectMapper, modules);
    }

    @Override
    public IndyStoresClientModule stores() throws IndyClientException {
        IndyStoresClientModule indyStore = Mockito.mock(IndyStoresClientModule.class);
        Mockito.when(indyStore.exists(any())).thenReturn(false);
        StoreListingDTO<RemoteRepository> storeListing = new StoreListingDTO<>();
        Mockito.when(indyStore.listRemoteRepositories(any())).thenReturn(storeListing);
        Mockito.when(indyStore.create(any(), anyString(), any())).thenReturn(null);

        HostedRepository hostedRepository = Mockito.mock(HostedRepository.class);
        Mockito.when(indyStore.load(any(), any())).thenReturn(hostedRepository);

        // Mockito.when(indyStore.update(any(), anyString())).thenReturn(hostedRepository);
        return indyStore;
    }

    public static class IndyFoloContentClientModuleMock extends IndyFoloContentClientModule {
        @Override
        public String trackingUrl(String id, StoreKey key) {
            return String.format(
                    "http://localhost/folo/track/%s/%s/%s/%s/",
                    id,
                    key.getPackageType(),
                    key.getType().singularEndpointName(),
                    key.getName());
        }
    }

    public static class IndyFoloAdminClientModuleMock extends IndyFoloAdminClientModule {
        @Override
        public boolean initReport(String trackingId) throws IndyClientException {
            return true;
        }

        @Override
        public void clearTrackingRecord(String trackingId) throws IndyClientException {
        }

        @Override
        public boolean sealTrackingRecord(String trackingId) throws IndyClientException {
            return true;
        }

        @Override
        public TrackedContentDTO getTrackingReport(String trackingId) throws IndyClientException {

            TrackedContentDTO report = new TrackedContentDTO();
            Set<TrackedContentEntryDTO> downloads = new HashSet<>();

            downloads.add(TrackingReportMocks.indyPomFromCentral);
            downloads.add(TrackingReportMocks.indyJarFromCentral);
            report.setDownloads(downloads);

            Set<TrackedContentEntryDTO> uploads = new HashSet<>();

            String buildContentId = "build-X";
            StoreKey buildKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, buildContentId);

            TrackedContentEntryDTO indyJarEntry = new TrackedContentEntryDTO(
                    buildKey,
                    AccessChannel.NATIVE,
                    TrackingReportMocks.indyJar);
            indyJarEntry.setMd5("abc");
            indyJarEntry.setSha1("abc");
            indyJarEntry.setSha256("abc");
            uploads.add(indyJarEntry);
            TrackedContentEntryDTO indyPomEntry = new TrackedContentEntryDTO(
                    buildKey,
                    AccessChannel.NATIVE,
                    TrackingReportMocks.indyPom);
            indyPomEntry.setMd5("abc");
            indyPomEntry.setSha1("abc");
            indyPomEntry.setSha256("abc");
            uploads.add(indyPomEntry);
            report.setUploads(uploads);

            return report;
        }
    }

    public static class IndyPromoteClientModuleMock extends IndyPromoteClientModule {
        @Override
        public PathsPromoteResult promoteByPath(PathsPromoteRequest req) throws IndyClientException {
            PathsPromoteResult pathsPromoteResult = Mockito.mock(PathsPromoteResult.class);
            Mockito.when(pathsPromoteResult.succeeded()).thenReturn(true);
            return pathsPromoteResult;
        }
    }
}
