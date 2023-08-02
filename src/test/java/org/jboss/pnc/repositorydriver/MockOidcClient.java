package org.jboss.pnc.repositorydriver;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Mock
public class MockOidcClient implements OidcClient {

    @Override
    public Uni<Tokens> getTokens(Map<String, String> additionalGrantParameters) {
        return Uni.createFrom()
                .item(new Tokens("accessToken", 1L, Duration.of(5, ChronoUnit.MINUTES), "refreshToken", 1L, null));
    }

    @Override
    public Uni<Tokens> refreshTokens(String refreshToken) {
        return null;
    }

    @Override
    public Uni<Boolean> revokeAccessToken(String accessToken) {
        return null;
    }

    @Override
    public void close() throws IOException {

    }
}
