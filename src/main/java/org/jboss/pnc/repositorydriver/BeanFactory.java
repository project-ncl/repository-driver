package org.jboss.pnc.repositorydriver;

import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLContext;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.module.IndyContentClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteConfigBuilder;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.repositorydriver.indy.IndyPNCOAuthBearerAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class BeanFactory {

    private static final Logger logger = LoggerFactory.getLogger(BeanFactory.class);

    @Inject
    Configuration configuration;

    @Inject
    IndyPNCOAuthBearerAuthenticator indyPNCOAuthBearerAuthenticator;

    protected SiteConfig indySiteConfig;
    protected IndyClientModule[] indyModules;

    @Inject
    ManagedExecutor executor;

    private HttpClient httpClient;

    @PostConstruct
    void init() throws NoSuchAlgorithmException {
        httpClient = java.net.http.HttpClient.newBuilder()
                .sslContext(SSLContext.getDefault())
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(configuration.getHttpClientConnectTimeout()))
                .build();

        String baseUrl = StringUtils.stripEnd(configuration.getIndyUrl(), "/");
        if (!baseUrl.endsWith("/api")) {
            baseUrl += "/api";
        }

        Boolean indyClientMetricsEnabled = configuration.getIndyClientMetricsEnabled();
        SiteConfigBuilder indySiteConfigBuilder = new SiteConfigBuilder("indy", baseUrl)
                .withRequestTimeoutSeconds(configuration.getIndyClientRequestTimeout())
                .withMaxConnections(10)
                .withMetricEnabled(indyClientMetricsEnabled);
        if (indyClientMetricsEnabled) {
            Optional<String> honeycombDataset = configuration.getIndyClientMetricsHoneycombDataset();
            Optional<String> honeycombWriteKey = configuration.getIndyClientMetricsHoneycombWriteKey();
            Optional<Integer> baseSampleRate = configuration.getIndyClientMetricsBaseSampleRate();
            if (honeycombDataset.isPresent()) {
                indySiteConfigBuilder.withHoneycombDataset(honeycombDataset.get());
            }
            if (honeycombWriteKey.isPresent()) {
                indySiteConfigBuilder.withHoneycombWriteKey(honeycombWriteKey.get());
            }
            if (baseSampleRate.isPresent()) {
                indySiteConfigBuilder.withBaseSampleRate(baseSampleRate.get());
            }
        }
        indySiteConfig = indySiteConfigBuilder.build();

        indyModules = new IndyClientModule[] {
                new IndyFoloAdminClientModule(),
                new IndyFoloContentClientModule(),
                new IndyPromoteClientModule() };
    }

    /**
     * Produces a per-request (@Dependent) Indy client. Each instance owns a jhttpc connection manager which starts a
     * background idle-connection-eviction thread ("jhttpc-connection-manager-cache"). The matching {@link #closeIndy}
     * disposer is invoked by CDI when the injecting bean is destroyed (end of request), which closes the client and
     * shuts that thread down. Without the disposer these threads would accumulate one per request and leak.
     *
     * <p>
     * Note: this request-scoped client must not be used by work that outlives the request (e.g. the asynchronous
     * promotion pipeline in {@code Driver#promote}), because the disposer closes its connection pool as soon as the
     * request returns. Such work must obtain a dedicated client via {@link #newIndyServiceAccountClient()} and close
     * it itself.
     */
    @Produces
    Indy createIndyServiceAccountClient() {
        return newIndyServiceAccountClient();
    }

    /**
     * Creates a new, unmanaged Indy client that is <b>not</b> tracked by CDI and therefore not closed by
     * {@link #closeIndy}. The caller owns its lifecycle and must call {@link Indy#close()} when done. Use this for
     * work that outlives the request scope so its connection pool stays open until the work completes.
     *
     * <p>
     * MDC-derived headers are captured at construction time, so this must be called on the request thread (before
     * the work is handed off to another thread) to capture the correct per-request context.
     */
    public Indy newIndyServiceAccountClient() {
        try {
            return new Indy(
                    indySiteConfig,
                    indyPNCOAuthBearerAuthenticator,
                    new IndyObjectMapper(true),
                    MdcUtils.mdcToMapWithHeaderKeys(),
                    indyModules);
        } catch (RepositoryDriverException | IndyClientException e) {
            logger.error("Failed to create Indy client: " + e.getMessage(), e);
            return null;
        }
    }

    void closeIndy(@Disposes Indy indy) {
        if (indy != null) {
            indy.close();
        }
    }

    @Produces
    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Produces
    public IndyContentClientModule getIndyContentClientModule() {
        return new IndyContentClientModule();
    }

    // >>> Indy client required beans - start

    // @Produces
    // public GoldenSignalsMetricSet clientMetricSet() {
    // return new ClientGoldenSignalsMetricSet();
    // }
    //
    // @Produces
    // public MetricsConfig metricsConfig() {
    // return new DefaultMetricsConfig();
    // }
    //
    // @Produces
    // public StoragePathProvider storagePathProvider() {
    // return () -> null;
    // }
    //
    // @Produces
    // public TrafficClassifier trafficClassifier() {
    // return new ClientTrafficClassifier();
    // }
    //
    // @Produces
    // public WeftConfig weftConfig() {
    // return new DefaultWeftConfig();
    // }

    // <<< Indy client required beans - end
}
