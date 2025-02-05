/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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

import io.quarkus.oidc.client.OidcClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.net.URI;

@ApplicationScoped
public class BifrostLogUploaderProducer {

    @Inject
    OidcClient oidcClient;
    private final BifrostLogUploader logUploader;

    public BifrostLogUploaderProducer(
            @ConfigProperty(name = "repository-driver.bifrost-uploader.api-url") URI bifrostUrl,
            @ConfigProperty(name = "repository-driver.bifrost-uploader.maxRetries", defaultValue = "6") int maxRetries,
            @ConfigProperty(
                    name = "repository-driver.bifrost-uploader.retryDelay",
                    defaultValue = "10") int retryDelay) {
        logUploader = new BifrostLogUploader(bifrostUrl, maxRetries, retryDelay, this::getFreshAccessToken);
    }

    private String getFreshAccessToken() {
        return oidcClient.getTokens().await().indefinitely().getAccessToken();
    }

    @Produces
    public BifrostLogUploader produce() {
        return logUploader;
    }
}