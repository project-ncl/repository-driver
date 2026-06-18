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

import jakarta.enterprise.context.Dependent;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.enums.BuildCategory;

import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Dependent
public class Configuration {
    private static final SmallRyeConfig CONFIG_READ = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .unwrap(SmallRyeConfig.class);

    @ConfigProperty(name = "repository-driver.deployment", defaultValue = "prod")
    DeploymentType deploymentType;

    @ConfigProperty(name = "repository-driver.environment", defaultValue = "DEV")
    String environment;

    @ConfigProperty(name = "repository-driver.self-base-url")
    String selfBaseUrl;

    @ConfigProperty(name = "repository-driver.artifactory-client.url")
    String artifactoryUrl;

    @ConfigProperty(name = "repository-driver.artifactory-client.access-token")
    String accessToken;

    @ConfigProperty(name = "repository-driver.indy-client.api-url")
    String indyUrl;

    @ConfigProperty(name = "repository-driver.archive-service.api-url")
    String archiveServiceEndpoint;

    @ConfigProperty(name = "repository-driver.archive-service.enabled", defaultValue = "true")
    boolean archiveServiceEnabled;

    @ConfigProperty(name = "repository-driver.indy-client.request-timeout", defaultValue = "30")
    Integer indyClientRequestTimeout;

    @ConfigProperty(name = "repository-driver.indy-client.metrics.enabled", defaultValue = "false")
    Boolean indyClientMetricsEnabled;

    @ConfigProperty(name = "repository-driver.indy-client.metrics.honeycombDataset", defaultValue = "")
    Optional<String> indyClientMetricsHoneycombDataset;

    @ConfigProperty(name = "repository-driver.indy-client.metrics.honeycombWriteKey", defaultValue = "")
    Optional<String> indyClientMetricsHoneycombWriteKey;

    @ConfigProperty(name = "repository-driver.indy-client.metrics.baseSampleRate", defaultValue = "0")
    Optional<Integer> indyClientMetricsBaseSampleRate;

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
    Duration keyCloakRequestTimeout;

    @ConfigProperty(name = "repository-driver.ignored-repo-patterns.archive")
    Optional<List<String>> ignoredRepoPatternsArchive;

    @ConfigProperty(name = "repository-driver.ignored-repo-patterns.promotion")
    Optional<List<String>> ignoredRepoPatternsPromotion;

    @ConfigProperty(name = "repository-driver.ignored-path-patterns.archive.maven")
    Optional<List<String>> ignoredPathPatternsArchiveMaven;

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

    @ConfigProperty(name = "repository-driver.indy-sidecar.enabled", defaultValue = "false")
    boolean sidecarEnabled;

    @ConfigProperty(name = "repository-driver.indy-sidecar.url")
    String sidecarUrl;

    @ConfigProperty(name = "repository-driver.indy-sidecar.archive-enabled", defaultValue = "false")
    boolean sidecarArchiveEnabled;

    @ConfigProperty(name = "repository-driver.archive-service.prefer-http-2", defaultValue = "true")
    boolean archiveServicePreferHttp2;

    @ConfigProperty(name = "repository-driver.archive-service.running-wait-for", defaultValue = "30")
    long archiveServiceRunningWaitFor;

    @ConfigProperty(name = "repository-driver.archive-service.running-retry-delay-msec", defaultValue = "500")
    long archiveServiceRunningRetryDelayMsec;

    @ConfigProperty(name = "repository-driver.archive-service.running-retry-max-delay-msec", defaultValue = "5000")
    long archiveServiceRunningRetryMaxDelayMsec;

    @ConfigProperty(name = "repository-driver.heartbeat.interval", defaultValue = "5")
    long heartbeatInterval;

    private static String getBuildCategoryConfig(String category, String leafConfig) {
        return "repository-driver.build-categories." + category + "." + leafConfig;
    }

    /**
     * get the config value for buildcategory. if no values specified for that buildcategory, use the 'default' one
     *
     * @param buildCategory
     * @param leafConfig
     * @return
     */
    private String getConfigString(BuildCategory buildCategory, String leafConfig) {

        if (buildCategory == null) {
            // fallback if buildCategory is null
            buildCategory = BuildCategory.STANDARD;
        }

        String buildCategoryConfig = getBuildCategoryConfig(buildCategory.name().toLowerCase(), leafConfig);
        String defaultBuildCategoryConfig = getBuildCategoryConfig("default", leafConfig);

        ConfigValue configValue = CONFIG_READ.getConfigValue(buildCategoryConfig);

        if (configValue.getValue() == null) {
            // if the raw value is null, assume that that config was never specified
            // get the default value instead
            return CONFIG_READ.getOptionalValue(defaultBuildCategoryConfig, String.class).orElse(null);
        } else {
            return configValue.getValue();
        }
    }

    /**
     * get the config value list for buildcategory. if no values specified for that buildcategory, use the 'default' one
     *
     * @param buildCategory
     * @param leafConfig
     * @return
     */
    private Optional<List<String>> getConfigListString(BuildCategory buildCategory, String leafConfig) {

        if (buildCategory == null) {
            // fallback if buildCategory is null
            buildCategory = BuildCategory.STANDARD;
        }

        String buildCategoryConfig = getBuildCategoryConfig(buildCategory.name().toLowerCase(), leafConfig);
        String defaultBuildCategoryConfig = getBuildCategoryConfig("default", leafConfig);

        ConfigValue configValue = CONFIG_READ.getConfigValue(buildCategoryConfig);

        if (configValue.getValue() == null) {
            // if the raw value is null, assume that that config was never specified
            // get the default value instead
            return CONFIG_READ.getOptionalValues(defaultBuildCategoryConfig, String.class);
        } else {
            return CONFIG_READ.getOptionalValues(buildCategoryConfig, String.class);
        }
    }

    public String getBuildPromotionTarget(BuildCategory buildCategory) {
        return getConfigString(buildCategory, "build-promotion-target");
    }

    public String getTempBuildPromotionTarget(BuildCategory buildCategory) {
        return getConfigString(buildCategory, "temp-build-promotion-target");
    }

    public Optional<List<String>> getBuildGroupConstituentsTempHosted(BuildCategory buildCategory) {
        return getConfigListString(buildCategory, "build-group-constituents.temp-hosted");
    }

    public Optional<List<String>> getBuildGroupConstituentsTempGroup(BuildCategory buildCategory) {
        return getConfigListString(buildCategory, "build-group-constituents.temp-group");
    }

    public Optional<List<String>> getBuildGroupConstituentsHosted(BuildCategory buildCategory) {
        return getConfigListString(buildCategory, "build-group-constituents.hosted");
    }

    public Optional<List<String>> getBuildGroupConstituentsGroup(BuildCategory buildCategory) {
        return getConfigListString(buildCategory, "build-group-constituents.group");
    }

    @ConfigProperty(name = "repository-driver.bifrost-uploader.enabled", defaultValue = "true")
    boolean bifrostUploaderEnabled;

    @ConfigProperty(name = "repository-driver.repository.naming-structure")
    String namingStructure;
}
