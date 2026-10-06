package org.jboss.pnc.repositorydriver.rest;

import static jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION;

import java.io.IOException;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.pnc.common.log.MDCUtils;
import org.jboss.pnc.quarkus.client.auth.runtime.PNCClientAuth;

@ApplicationScoped
public class PNCAuthClientRequestFilter implements ClientRequestFilter {

    @Inject
    PNCClientAuth pncClientAuth;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> reqHeaders = requestContext.getHeaders();

        // adding tracing headers from MDC
        Map<String, String> headers = MDCUtils.getHeadersFromMDC();
        if (headers != null) {
            headers.forEach(reqHeaders::putSingle);
        }

        // adding auth header from PNCClientAuth
        String authHeader = pncClientAuth.getHttpAuthorizationHeaderValueWithCachedToken();
        if (authHeader != null && !authHeader.isBlank()) {
            reqHeaders.putSingle(AUTHORIZATION, authHeader);
        }
    }
}
