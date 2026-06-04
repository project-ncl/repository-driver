/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.repositorydriver.runtime;

import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import javax.net.ssl.SSLContext;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.repositorydriver.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producer for HttpClient.
 * Creates and configures a java.net.http.HttpClient instance for use throughout the application.
 *
 */
@ApplicationScoped
public class HttpClientProducer {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientProducer.class);

    @Inject
    Configuration configuration;

    @Inject
    ManagedExecutor executor;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        try {
            httpClient = HttpClient.newBuilder()
                    .sslContext(SSLContext.getDefault())
                    .executor(executor)
                    .connectTimeout(Duration.ofSeconds(configuration.getHttpClientConnectTimeout()))
                    .build();
            logger.info("HttpClient initialized successfully");
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to initialize HttpClient: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize HttpClient", e);
        }
    }

    @Produces
    public HttpClient getHttpClient() {
        return httpClient;
    }

    @PreDestroy
    void destroy() {
        // HttpClient doesn't require explicit cleanup, but we log for consistency
        logger.info("HttpClientProducer destroyed");
    }
}

// Made with Bob
