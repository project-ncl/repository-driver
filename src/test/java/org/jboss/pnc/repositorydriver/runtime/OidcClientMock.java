package org.jboss.pnc.repositorydriver.runtime;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Mock
public class OidcClientMock implements OidcClient {

    @Override
    public Uni<Tokens> getTokens() {
        Tokens tokens = new Tokens("theToken", Long.MAX_VALUE, Duration.ofDays(1), "refreshToken");
        return Uni.createFrom().completionStage(CompletableFuture.completedStage(tokens));
    }

    @Override
    public Uni<Tokens> refreshTokens(String refreshToken) {
        return getTokens();
    }

    @Override
    public void close() throws IOException {

    }
}
