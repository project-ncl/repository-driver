package org.jboss.pnc.repositorydriver.indy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.http.Header;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.util.jhttpc.JHttpCException;
import org.jboss.pnc.quarkus.client.auth.runtime.PNCClientAuth;

@ApplicationScoped
public class IndyPNCOAuthBearerAuthenticator extends IndyClientAuthenticator {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    @Inject
    PNCClientAuth pncClientAuth;

    @Override
    public HttpClientBuilder decorateClientBuilder(HttpClientBuilder builder) throws JHttpCException {
        builder.addInterceptorFirst((HttpRequestInterceptor) (httpRequest, httpContext) -> {
            final Header header = new BasicHeader(
                    AUTHORIZATION_HEADER,
                    pncClientAuth.getHttpAuthorizationHeaderValue());
            httpRequest.addHeader(header);
        });
        return builder;
    }
}