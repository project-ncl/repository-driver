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
package org.jboss.pnc.repositorydriver;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.Dependent;

import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Getter
@Setter
@Dependent
public class Configuration {

    @ConfigProperty(name = "repository-driver.self-base-url")
    String selfBaseUrl;

    @ConfigProperty(name = "repository-driver.indy-client.api-url")
    String indyUrl;

    @ConfigProperty(name = "repository-driver.indy-client.request-timeout", defaultValue = "30")
    Integer indyClientRequestTimeout;

    @ConfigProperty(name = "repository-driver.http-client.connect-timeout", defaultValue = "5")
    int httpClientConnectTimeout;

    @ConfigProperty(name = "repository-driver.http-client.request-timeout", defaultValue = "15")
    int httpClientRequestTimeout;

    @ConfigProperty(name = "repository-driver.callback-retry-duration", defaultValue = "600")
    long callbackRetryDuration;

    @ConfigProperty(name = "repository-driver.callback-retry-delay-msec", defaultValue = "500")
    long callbackRetryDelayMsec;

    @ConfigProperty(name = "repository-driver.callback-retry-max-delay-msec", defaultValue = "5000")
    long callbackRetryMaxDelayMsec;

    @ConfigProperty(name = "repository-driver.keycloak.request-timeout", defaultValue = "PT10S")
    private Duration keyCloakRequestTimeout;

    @ConfigProperty(name = "repository-driver.build-promotion-target")
    String buildPromotionTarget;

    @ConfigProperty(name = "repository-driver.temp-build-promotion-target")
    String tempBuildPromotionTarget;

    @ConfigProperty(name = "repository-driver.ignored-repo-patterns")
    Optional<List<String>> ignoredRepoPatterns;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.promotion.generic")
    Optional<List<String>> ignoredPathPatternsPromotionGeneric;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.promotion.maven")
    Optional<List<String>> ignoredPathPatternsPromotionMaven;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.promotion.npm")
    Optional<List<String>> ignoredPathPatternsPromotionNpm;

    // ArtifactFilterImpl.IgnoredPatterns ignoredPathPatterns.data
    @ConfigProperty(name = "repository-driver.ignored-path-patterns.result.generic")
    Optional<List<String>> ignoredPathPatternsResultGeneric;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.result.maven")
    Optional<List<String>> ignoredPathPatternsResultMaven;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.result.npm")
    Optional<List<String>> ignoredPathPatternsResultNpm;

}
