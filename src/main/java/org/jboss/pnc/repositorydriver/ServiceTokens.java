package org.jboss.pnc.repositorydriver;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Singleton
public class ServiceTokens {

    @Inject
    OidcClient client;

    @Inject
    Configuration configuration;

    private volatile Tokens currentTokens;

    public String getAccessToken() {
        if (currentTokens == null) {
            currentTokens = client.getTokens().await().atMost(configuration.getKeyCloakRequestTimeout());
        }
        Tokens tokens = currentTokens;
        if (tokens.isAccessTokenExpired()) {
            tokens = client.refreshTokens(tokens.getRefreshToken())
                    .await()
                    .atMost(configuration.getKeyCloakRequestTimeout());
            currentTokens = tokens;
        }
        return tokens.getAccessToken();
    }
}
