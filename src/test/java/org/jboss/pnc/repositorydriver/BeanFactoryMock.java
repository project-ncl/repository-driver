package org.jboss.pnc.repositorydriver;

import javax.enterprise.inject.Produces;

import io.quarkus.test.Mock;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.indy.client.core.auth.OAuth20BearerTokenAuthenticator;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Mock
public class BeanFactoryMock extends BeanFactory {

    private static final Logger logger = LoggerFactory.getLogger(BeanFactoryMock.class);

    @Produces
    Indy createIndyServiceAccountClient() {
        IndyClientModule[] indyModules = new IndyClientModule[]{new IndyMock.IndyFoloAdminClientModuleMock(), new IndyMock.IndyFoloContentClientModuleMock(),
                new IndyMock.IndyPromoteClientModuleMock()};

        IndyClientAuthenticator authenticator = new OAuth20BearerTokenAuthenticator(serviceTokens.getAccessToken());
        try {
            indy = new IndyMock(
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
}
