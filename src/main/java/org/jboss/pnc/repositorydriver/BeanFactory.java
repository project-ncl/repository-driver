package org.jboss.pnc.repositorydriver;

import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.net.ssl.SSLContext;

import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.indy.client.core.auth.OAuth20BearerTokenAuthenticator;
import org.commonjava.indy.client.core.module.IndyContentClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteConfigBuilder;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.oidc.client.Tokens;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class BeanFactory {

    private static final Logger logger = LoggerFactory.getLogger(BeanFactory.class);

    @Inject
    Configuration configuration;

    @Inject
    Tokens serviceTokens;

    protected SiteConfig indySiteConfig;
    protected IndyClientModule[] indyModules;
    protected Indy indy;

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

        indySiteConfig = new SiteConfigBuilder("indy", baseUrl)
                .withRequestTimeoutSeconds(configuration.getIndyClientRequestTimeout())
                .withMaxConnections(10)
                .build();

        indyModules = new IndyClientModule[] { new IndyFoloAdminClientModule(), new IndyFoloContentClientModule(),
                new IndyPromoteClientModule() };
    }

    @Produces
    synchronized Indy createIndyServiceAccountClient() {
        IndyClientAuthenticator authenticator = new OAuth20BearerTokenAuthenticator(serviceTokens.getAccessToken());
        try {
            indy = new Indy(
                    indySiteConfig,
                    authenticator,
                    new IndyObjectMapper(true),
                    MdcUtils.mdcToMapWithHeaderKeys(),
                    indyModules);
            return indy;
        } catch (RepositoryDriverException | IndyClientException e) {
            logger.error("Failed to create Indy client: " + e.getMessage(), e);
            return null;
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

    @PreDestroy
    void destroy() {
        indy.close();
    }

}
