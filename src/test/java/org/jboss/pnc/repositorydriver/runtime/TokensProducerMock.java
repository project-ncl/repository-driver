package org.jboss.pnc.repositorydriver.runtime;

import java.time.Duration;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.inject.Produces;

import io.quarkus.oidc.client.Tokens;
import io.quarkus.test.Mock;

/**
 * @author <a href="mailto:jbrazdil@gmail.com">Honza Brázdil</a>
 */
@Mock
public class TokensProducerMock {

    @Produces
    @RequestScoped
    public Tokens produceTokens() {
        Tokens tokens = new Tokens("theToken", Long.MAX_VALUE, Duration.ofDays(1), "refreshToken", null, null);

        return tokens;
    }
}
