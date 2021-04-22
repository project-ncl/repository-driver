package org.jboss.pnc.repositorydriver;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.indy.client.core.auth.OAuth20BearerTokenAuthenticator;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteConfigBuilder;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class BeanFactory {

    @Inject
    ServiceTokens serviceTokens;

    private Indy indy;

    @Produces
    @ApplicationScoped
    Indy createIndyServiceAccountClient() {
        IndyClientAuthenticator authenticator = new OAuth20BearerTokenAuthenticator(serviceTokens.getAccessToken());

        try {
            SiteConfig siteConfig = new SiteConfigBuilder("indy", baseUrl)
                    .withRequestTimeoutSeconds(DEFAULT_REQUEST_TIMEOUT)
                    // this client is used in single build, we don't need more than 1 connection at a time
                    .withMaxConnections(1)
                    .build();

            IndyClientModule[] modules = new IndyClientModule[] { new IndyFoloAdminClientModule(),
                    new IndyFoloContentClientModule(), new IndyPromoteClientModule() };

            indy = new Indy(
                    siteConfig,
                    authenticator,
                    new IndyObjectMapper(true),
                    mdcToMapWithHeaderKeys(),
                    modules);
            return indy;
        } catch (IndyClientException e) {
            throw new IllegalStateException("Failed to create Indy client: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void destroy() {
        indy.close();
    }

}
