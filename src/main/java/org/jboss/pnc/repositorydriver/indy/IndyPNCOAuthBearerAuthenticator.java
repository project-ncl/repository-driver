package org.jboss.pnc.repositorydriver.indy;

/*
 * @ApplicationScoped
 * public class IndyPNCOAuthBearerAuthenticator extends IndyClientAuthenticator {
 * 
 * private static final String AUTHORIZATION_HEADER = "Authorization";
 * 
 * @Inject
 * PNCClientAuth pncClientAuth;
 * 
 * @Override
 * public HttpClientBuilder decorateClientBuilder(HttpClientBuilder builder) throws JHttpCException {
 * builder.addInterceptorFirst((HttpRequestInterceptor) (httpRequest, httpContext) -> {
 * final Header header = new BasicHeader(
 * AUTHORIZATION_HEADER,
 * pncClientAuth.getHttpAuthorizationHeaderValue());
 * httpRequest.addHeader(header);
 * });
 * return builder;
 * }
 * }
 */