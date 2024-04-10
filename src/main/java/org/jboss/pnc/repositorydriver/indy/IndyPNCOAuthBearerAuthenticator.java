package org.jboss.pnc.repositorydriver.indy;

import io.quarkus.oidc.client.OidcClient;
import org.apache.http.Header;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.util.jhttpc.JHttpCException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class IndyPNCOAuthBearerAuthenticator extends IndyClientAuthenticator {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String BEARER_FORMAT = "Bearer %s";

    @Inject
    OidcClient oidcClient;

    @Override
    public HttpClientBuilder decorateClientBuilder(HttpClientBuilder builder) throws JHttpCException {
        builder.addInterceptorFirst((HttpRequestInterceptor) (httpRequest, httpContext) -> {
            final Header header = new BasicHeader(
                    AUTHORIZATION_HEADER,
                    String.format(BEARER_FORMAT, getFreshAccessToken()));
            httpRequest.addHeader(header);
        });
        return builder;
    }

    private String getFreshAccessToken() {
        return oidcClient.getTokens().await().indefinitely().getAccessToken();
    }
}