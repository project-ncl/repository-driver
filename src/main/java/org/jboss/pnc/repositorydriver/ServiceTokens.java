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

    private volatile Tokens currentTokens;

    public String getAccessToken() {
        if (currentTokens == null) { //TODO is this correct ?
            currentTokens = client.getTokens().await().indefinitely();
        }
        Tokens tokens = currentTokens;
        if (tokens.isAccessTokenExpired()) {
            tokens = client.refreshTokens(tokens.getRefreshToken()).await().indefinitely();
            currentTokens = tokens;
        }
        return tokens.getAccessToken();
    }
}
