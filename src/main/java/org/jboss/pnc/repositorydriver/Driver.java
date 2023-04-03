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
package org.jboss.pnc.repositorydriver;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.context.Context;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.oidc.client.Tokens;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.event.ExecutionAttemptedEvent;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.module.IndyStoresClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.indy.promote.model.AbstractPromoteResult;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.indy.promote.model.ValidationResult;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.api.constants.MDCKeys;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.api.repositorydriver.dto.ArchiveRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.common.otel.OtelUtils;
import org.jboss.pnc.repositorydriver.runtime.ApplicationLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.jboss.pnc.api.constants.HttpHeaders.AUTHORIZATION_STRING;
import static org.jboss.pnc.api.constants.HttpHeaders.CONTENT_TYPE_STRING;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@RequestScoped
public class Driver {

    /** Store key of gradle-plugins remote repository. */
    static final String GRADLE_PLUGINS_REPO = "maven:remote:gradle-plugins";

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);
    private static final Logger userLog = LoggerFactory.getLogger("org.jboss.pnc._userlog_.repository-driver");

    public static final String BREW_PULL_METADATA_KEY = "koji-pull";

    @Inject
    ManagedExecutor executor;

    @Inject
    Configuration configuration;

    @Inject
    java.net.http.HttpClient httpClient;

    @Inject
    ObjectMapper jsonMapper;

    @Inject
    Indy indy;

    @Inject
    ApplicationLifecycle lifecycle;

    @Inject
    TrackingReportProcessor trackingReportProcessor;

    @Inject
    Tokens serviceTokens;

    @WithSpan()
    public RepositoryCreateResponse create(
            @SpanAttribute(value = "repositoryCreateRequest") RepositoryCreateRequest repositoryCreateRequest)
            throws RepositoryDriverException {
        BuildType buildType = repositoryCreateRequest.getBuildType();
        String packageType = TypeConverters.getIndyPackageTypeKey(buildType.getRepoType());
        String buildId = repositoryCreateRequest.getBuildContentId();

        try {
            setupBuildRepos(
                    repositoryCreateRequest.getBuildContentId(),
                    buildType,
                    packageType,
                    repositoryCreateRequest.isTempBuild(),
                    repositoryCreateRequest.isBrewPullActive(),
                    repositoryCreateRequest.getExtraRepositories());
        } catch (IndyClientException e) {
            logger.debug("Failed to setup repository or repository group for this build");
            throw new RepositoryDriverException(
                    "Failed to setup repository or repository group for this build: %s",
                    e,
                    e.getMessage());
        }

        String downloadsUrl;
        String deployUrl;

        try {
            // manually initialize the tracking record, just in case (somehow) nothing gets downloaded/uploaded.
            IndyFoloAdminClientModule foloAdminModule = indy.module(IndyFoloAdminClientModule.class);
            foloAdminModule.clearTrackingRecord(buildId);
            foloAdminModule.initReport(buildId);

            StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildId);
            downloadsUrl = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, groupKey);

            StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildId);
            deployUrl = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, hostedKey);

            if (configuration.isSidecarEnabled()) {
                logger.info("Indy sidecar feature enabled: replacing Indy host with Indy sidecar host");
                try {
                    downloadsUrl = UrlUtils.replaceHostInUrl(downloadsUrl, configuration.getSidecarUrl());
                } catch (MalformedURLException e) {
                    throw new RuntimeException(
                            String.format(
                                    "Indy sidecar url ('%s') or Indy urls ('%s',  '%s') are url malformed!",
                                    configuration.getSidecarUrl(),
                                    downloadsUrl,
                                    deployUrl));
                }
            }

            logger.info("Using '{}' for {} repository access in build: {}", downloadsUrl, packageType, buildId);
        } catch (IndyClientException e) {
            logger.debug("Failed to retrieve Indy client module for the artifact tracker");
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy client module for the artifact tracker: %s",
                    e,
                    e.getMessage());
        }
        return new RepositoryCreateResponse(
                downloadsUrl,
                deployUrl,
                configuration.isSidecarEnabled(),
                configuration.isSidecarArchiveEnabled());
    }

    /**
     * Retrieve tracking report from repository manager. Add each tracked download to the dependencies of the build
     * result. Add each tracked upload to the built artifacts of the build result. Promote uploaded artifacts to the
     * product-level storage. Finally delete the group associated with the completed build.
     */
    @WithSpan()
    public void promote(@SpanAttribute(value = "promoteRequest") RepositoryPromoteRequest promoteRequest)
            throws RepositoryDriverException {
        if (lifecycle.isShuttingDown()) {
            throw new StoppingException();
        }
        String buildContentId = promoteRequest.getBuildContentId();
        String buildConfigurationId = promoteRequest.getBuildConfigurationId();
        BuildType buildType = promoteRequest.getBuildType();
        TrackedContentDTO report = retrieveTrackingReport(buildContentId);
        Set<StoreKey> genericRepos = new HashSet<>();

        // removeActivePromotion is called as the last step of Driver#notifyInvoker
        lifecycle.addActivePromotion();
        // schedule promotion
        executor.runAsync(Context.current().wrap(() -> {
            Request heartBeat = promoteRequest.getHeartBeat();
            Runnable heartBeatSender;
            if (heartBeat != null) {
                heartBeatSender = heartBeatSender(heartBeat);
            } else {
                heartBeatSender = () -> {};
            }

            List<RepositoryArtifact> downloadedArtifacts;
            List<RepositoryArtifact> uploadedArtifacts;
            try {
                downloadedArtifacts = trackingReportProcessor.collectDownloadedArtifacts(report);
                heartBeatSender.run();
                uploadedArtifacts = trackingReportProcessor.collectUploadedArtifacts(
                        report,
                        promoteRequest.isTempBuild(),
                        promoteRequest.getBuildCategory());
            } catch (RepositoryDriverException e) {
                logger.error("Failed collecting downloaded or uploaded artifacts.", e);
                String message = e.getMessage();
                notifyInvoker(
                        promoteRequest.getCallback(),
                        RepositoryPromoteResult.failed(buildContentId, message, ResultStatus.SYSTEM_ERROR));
                return;
            }

            try {
                // the promotion is done only after a successfully collected downloads and uploads
                heartBeatSender.run();
                PromotionPaths downloadsPromotions = trackingReportProcessor
                        .collectDownloadsPromotions(report, genericRepos);
                promoteDownloads(downloadsPromotions, heartBeatSender, promoteRequest.isTempBuild());
                heartBeatSender.run();
                promoteUploads(
                        trackingReportProcessor.collectUploadsPromotions(
                                report,
                                promoteRequest.isTempBuild(),
                                buildType.getRepoType(),
                                buildContentId),
                        promoteRequest.isTempBuild(),
                        heartBeatSender);
            } catch (RepositoryDriverException e) {
                logger.error("Failed promoting downloaded or uploaded artifacts.", e);

                notifyInvoker(
                        promoteRequest.getCallback(),
                        RepositoryPromoteResult.failed(buildContentId, e.getMessage(), ResultStatus.SYSTEM_ERROR));
                return;
            } catch (PromotionValidationException e) {
                logger.warn("Failed promoting downloaded or uploaded artifacts.", e);

                notifyInvoker(
                        promoteRequest.getCallback(),
                        RepositoryPromoteResult.failed(buildContentId, e.getMessage(), ResultStatus.FAILED));
                return;
            }

            logger.info("{} uploaded {} artifacts", buildContentId, uploadedArtifacts.size());
            logger.info("{} downloaded {} artifacts", buildContentId, downloadedArtifacts.size());

            if (logger.isDebugEnabled()) {
                logger.debug("Returning built artifacts / dependencies");
                uploadedArtifacts
                        .forEach(artifact -> logger.debug("{} uploaded: {}", buildContentId, artifact.toString()));
                downloadedArtifacts
                        .forEach(artifact -> logger.debug("{} downloaded: {}", buildContentId, artifact.toString()));
            }

            notifyInvoker(
                    promoteRequest.getCallback(),
                    new RepositoryPromoteResult(
                            uploadedArtifacts,
                            downloadedArtifacts,
                            buildContentId,
                            "",
                            ResultStatus.SUCCESS));
        })).thenRunAsync(Context.current().wrap(() -> {
            // CLEANUP
            try {
                logger.info(
                        "Deleting build group {} {} and the generic http repositories...",
                        buildType.getRepoType(),
                        buildContentId);
                deleteBuildRepos(buildType.getRepoType(), buildContentId, genericRepos);
            } catch (Throwable e) {
                logger.error("Failed to delete build group.", e);
            }
        })).handle(Context.current().wrapFunction((nul, throwable) -> {
            if (throwable != null) {
                logger.error("Unhanded promotion exception.", throwable);
            } else {
                if (configuration.isSidecarArchiveEnabled()) {
                    // Archive the downloaded artifacts
                    try {
                        ArchiveRequest archiveRequest = ArchiveRequest.builder()
                                .buildConfigId(buildConfigurationId)
                                .buildContentId(buildContentId)
                                .build();
                        logger.info(
                                "Archiving the downloaded content of {} for build {} ...",
                                buildConfigurationId,
                                buildContentId);

                        archive(archiveRequest);
                    } catch (Throwable e) {
                        logger.error(
                                "Failed to archive the downloaded content of {} for build {} ...",
                                buildConfigurationId,
                                buildContentId,
                                e);
                    }
                }
            }
            lifecycle.removeActivePromotion();
            return null;
        }));
    }

    @WithSpan()
    public void archive(@SpanAttribute(value = "archiveRequest") ArchiveRequest request)
            throws RepositoryDriverException {

        TrackedContentDTO report = retrieveTrackingReport(request.getBuildContentId());
        doArchive(request, report);
    }

    private void doArchive(
            @SpanAttribute(value = "archiveRequest") ArchiveRequest request,
            @SpanAttribute(value = "report") TrackedContentDTO report) throws RepositoryDriverException {

        // Create a parent child span with values from MDC
        SpanBuilder spanBuilder = OtelUtils.buildChildSpan(
                GlobalOpenTelemetry.get().getTracer(""),
                "Driver.doArchive",
                SpanKind.CLIENT,
                MDC.get(MDCKeys.TRACE_ID_KEY),
                MDC.get(MDCKeys.SPAN_ID_KEY),
                MDC.get(MDCKeys.TRACE_FLAGS_KEY),
                MDC.get(MDCKeys.TRACE_STATE_KEY),
                Span.current().getSpanContext(),
                Map.of("buildContentId", request.getBuildContentId(), "buildConfigId", request.getBuildConfigId()));
        Span span = spanBuilder.startSpan();
        logger.debug("Started a new span :{}", span);

        // put the span into the current Context
        try (Scope scope = span.makeCurrent()) {

            logger.info("Retrieving tracking report and filtering artifacts to archive.");
            List<ArchiveDownloadEntry> toArchive = trackingReportProcessor.collectArchivalArtifacts(report);

            logger.info("Retrieved these artifacts {}", toArchive);

            ArchivePayload archiveRequest = ArchivePayload.builder()
                    .buildConfigId(request.getBuildConfigId())
                    .downloads(toArchive)
                    .build();

            requestArchival(archiveRequest);
        } finally {
            span.end(); // closing the scope does not end the span, this has to be done manually
        }
    }

    private HttpResponse<String> requestArchival(ArchivePayload archivePayload) {
        logger.info("Invoking archival service. Request: {}", archivePayload);
        String body;
        try {
            body = jsonMapper.writeValueAsString(archivePayload);
        } catch (JsonProcessingException e) {
            logger.error("Cannot serialize callback object.", e);
            body = "";
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(configuration.getArchiveServiceEndpoint()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()))
                .header(AUTHORIZATION_STRING, "Bearer " + serviceTokens.getAccessToken())
                .header(CONTENT_TYPE_STRING, "application/json");

        HttpRequest request = builder.build();
        RetryPolicy<HttpResponse<String>> retryPolicy = new RetryPolicy<HttpResponse<String>>()
                .withMaxDuration(Duration.ofSeconds(configuration.getArchiveServiceRunningWaitFor()))
                .withMaxRetries(Integer.MAX_VALUE) // retry until maxDuration is reached
                .withBackoff(
                        configuration.getArchiveServiceRunningRetryDelayMsec(),
                        configuration.getArchiveServiceRunningRetryMaxDelayMsec(),
                        ChronoUnit.MILLIS)
                .onSuccess(
                        ctx -> logger
                                .info("Archival service responded, response status: {}.", ctx.getResult().statusCode()))
                .onRetry(ctx -> {
                    String lastError;
                    if (ctx.getLastFailure() != null) {
                        lastError = ctx.getLastFailure().getMessage();
                    } else {
                        lastError = "";
                    }
                    Integer lastStatus;
                    if (ctx.getLastResult() != null) {
                        lastStatus = ctx.getLastResult().statusCode();
                    } else {
                        lastStatus = null;
                    }
                    logger.warn(
                            "Archival service call retry attempt #{}, last error: [{}], last status: [{}].",
                            ctx.getAttemptCount(),
                            lastError,
                            lastStatus);
                })
                .onFailure(ctx -> logger.error("Unable to call archival service: {}.", ctx.getFailure().getMessage()))
                .onAbort(e -> logger.warn("Archival service call aborted: {}.", e.getFailure().getMessage()));

        logger.info("About to call archival service {}.", request.uri());
        return Failsafe.with(retryPolicy)
                .with(executor)
                .getStageAsync(
                        () -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .thenApply(validateResponse()))
                .join();
    }

    private static void onRetry(ExecutionAttemptedEvent<HttpResponse<String>> ctx, String operation) {
        String lastError;
        if (ctx.getLastFailure() != null) {
            lastError = ctx.getLastFailure().getMessage();
        } else {
            lastError = "";
        }
        Integer lastStatus;
        if (ctx.getLastResult() != null) {
            lastStatus = ctx.getLastResult().statusCode();
        } else {
            lastStatus = null;
        }
        logger.warn(
                "{} retry attempt #{}, last error: [{}], last status: [{}].",
                operation,
                ctx.getAttemptCount(),
                lastError,
                lastStatus);
    }

    private void notifyInvoker(Request callback, RepositoryPromoteResult promoteResult) {
        String body;
        try {
            body = jsonMapper.writeValueAsString(promoteResult);
        } catch (JsonProcessingException e) {
            logger.error("Cannot serialize callback object.", e);
            body = "";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(callback.getUri())
                .method(callback.getMethod().name(), HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()));
        callback.getHeaders().forEach(h -> builder.header(h.getName(), h.getValue()));
        HttpRequest request = builder.build();

        RetryPolicy<HttpResponse<String>> retryPolicy = new RetryPolicy<HttpResponse<String>>()
                .withMaxDuration(Duration.ofSeconds(configuration.getCallbackRetryDuration()))
                .withMaxRetries(Integer.MAX_VALUE) // retry until maxDuration is reached
                .withBackoff(
                        configuration.getCallbackRetryDelayMsec(),
                        configuration.getCallbackRetryMaxDelayMsec(),
                        ChronoUnit.MILLIS)
                .onSuccess(ctx -> logger.info("Callback sent, response status: {}.", ctx.getResult().statusCode()))
                .onRetry(ctx -> onRetry(ctx, "Callback"))
                .onFailure(ctx -> logger.error("Unable to send callback."))
                .onAbort(e -> logger.warn("Callback aborted: {}.", e.getFailure().getMessage()));
        Failsafe.with(retryPolicy)
                .with(executor)
                .getStageAsync(
                        () -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .thenApply(validateResponse()))
                .handle(Context.current().wrapFunction((r, t) -> {
                    lifecycle.removeActivePromotion();
                    return null;
                }));
    }

    @WithSpan()
    public RepositoryPromoteResult collectRepoManagerResult(
            @SpanAttribute(value = "buildContentId") String buildContentId,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "buildCategory") BuildCategory buildCategory) throws RepositoryDriverException {
        TrackedContentDTO report = retrieveTrackingReport(buildContentId);
        try {
            List<RepositoryArtifact> downloadedArtifacts = trackingReportProcessor.collectDownloadedArtifacts(report);
            List<RepositoryArtifact> uploadedArtifacts = trackingReportProcessor
                    .collectUploadedArtifacts(report, tempBuild, buildCategory);

            logger.info(
                    "Returning built artifacts / dependencies:\nUploads:\n  {}\n\nDownloads:\n  {}\n\n",
                    StringUtils.join(uploadedArtifacts, "\n  "),
                    StringUtils.join(downloadedArtifacts, "\n  "));
            return new RepositoryPromoteResult(
                    uploadedArtifacts,
                    downloadedArtifacts,
                    buildContentId,
                    "",
                    ResultStatus.SUCCESS);
        } catch (RepositoryDriverException e) {
            String message = e.getMessage();
            userLog.error("Failed to collect artifacts. Error(s): {}", message);
            return new RepositoryPromoteResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    buildContentId,
                    message,
                    ResultStatus.FAILED);
        }
    }

    /**
     * Create the hosted repository and group necessary to support a single build. The hosted repository holds artifacts
     * uploaded from the build, and the group coordinates access to this hosted repository, along with content from the
     * product-level content group with which this build is associated. The group also provides a tracking target, so
     * the repository manager can keep track of downloads and uploads for the build.
     *
     * @param packageType the package type key used by Indy
     */
    private void setupBuildRepos(
            String buildContentId,
            BuildType buildType,
            String packageType,
            boolean tempBuild,
            boolean brewPullActive,
            List<String> extraDependencyRepositories) throws IndyClientException {

        // if the build-level group doesn't exist, create it.
        StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildContentId);
        StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildContentId);

        // if the group and repo exist, delete them and recreate them from scratch
        IndyStoresClientModule storesModule = indy.stores();
        if (storesModule.exists(groupKey)) {
            String logCleanupGroupKey = "Cleanup " + groupKey + " before build run.";
            logger.info(logCleanupGroupKey);
            storesModule.delete(groupKey, logCleanupGroupKey);
        }
        if (storesModule.exists(hostedKey)) {
            HostedRepository hosted = storesModule.load(hostedKey, HostedRepository.class);
            if (hosted.isReadonly()) {
                hosted.setReadonly(false);
                String logWritableHostKey = "Make " + hostedKey + " writable before delete.";
                logger.info(logWritableHostKey);
                storesModule.update(hosted, logWritableHostKey);
            }

            String logCleanupHostedKey = "Cleanup " + hostedKey + " before build run.";
            logger.info(logCleanupHostedKey);
            storesModule.delete(hostedKey, logCleanupHostedKey, true);
        }

        // create build repo
        HostedRepository buildArtifacts = new HostedRepository(packageType, buildContentId);
        buildArtifacts.setAllowSnapshots(false);
        buildArtifacts.setAllowReleases(true);

        buildArtifacts.setDescription(String.format("Build output for PNC %s build #%s", packageType, buildContentId));

        String logCreatingHostedRepo = "Creating hosted repository for " + packageType + " build: " + buildContentId
                + " (repo: " + buildContentId + ")";
        logger.info(logCreatingHostedRepo);
        storesModule.create(buildArtifacts, logCreatingHostedRepo, HostedRepository.class);

        // create build group
        Group buildGroup = BuildGroupBuilder.builder(indy, packageType, buildContentId)
                .withDescription(
                        String.format(
                                "Aggregation group for PNC %s build #%s",
                                tempBuild ? "temporary " : "",
                                buildContentId))
                // build-local artifacts
                .addConstituent(hostedKey)
                // Global-level repos, for captured/shared artifacts and access to the outside world
                .addGlobalConstituents(buildType, tempBuild)
                // build-specific repos
                .addExtraConstituents(extraDependencyRepositories)
                // brew pull: see MMENG-1262
                .addMetadata(BREW_PULL_METADATA_KEY, Boolean.toString(brewPullActive))
                .build();

        String changelog = "Creating repository group for resolving artifacts (repo: " + buildContentId + ").";
        logger.info(changelog);
        storesModule.create(buildGroup, changelog, Group.class);
    }

    /**
     * Promotes by path downloads captured in given map. The key in the map is promotion target store key. The value is
     * another map, where key is promotion source store key and value is list of paths to be promoted.
     *
     * @throws RepositoryDriverException in case of an unexpected error during promotion
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     */
    private void promoteDownloads(PromotionPaths promotionPaths, Runnable heartBeatSender, boolean tempBuild)
            throws RepositoryDriverException, PromotionValidationException {
        // Promote all build dependencies NOT ALREADY CAPTURED to the hosted repository holding store for the shared
        // imports
        for (SourceTargetPaths sourceTargetPaths : promotionPaths.getSourceTargetsPaths()) {
            heartBeatSender.run();
            PathsPromoteRequest request = new PathsPromoteRequest(
                    sourceTargetPaths.getSource(),
                    sourceTargetPaths.getTarget(),
                    sourceTargetPaths.getPaths());
            request.setPurgeSource(false);
            // set read-only only the generic http proxy hosted repos, not shared-imports
            boolean readonly = !tempBuild && GENERIC_PKG_KEY.equals(sourceTargetPaths.getTarget().getPackageType());

            try {
                userLog.info(
                        "Promoting {} dependencies from {} to {}",
                        request.getPaths().size(),
                        request.getSource(),
                        request.getTarget());
                doPromoteByPath(request, false, readonly);
            } catch (RepositoryDriverException ex) {
                userLog.error("Failed to promote by path. Error(s): {}", ex.getMessage());
                throw ex;
            }
        }
    }

    /**
     * Promote the build output to the consolidated build repo (using path promotion, where the build repo contents are
     * added to the repo's contents) and marks the build output as readonly.
     *
     * @throws RepositoryDriverException when the repository client API throws an exception due to something unexpected
     *         in transport
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     */
    private void promoteUploads(PromotionPaths promotionPaths, boolean tempBuild, Runnable heartBeatSender)
            throws RepositoryDriverException, PromotionValidationException {
        for (SourceTargetPaths sourceTargetPaths : promotionPaths.getSourceTargetsPaths()) {
            heartBeatSender.run();
            try {
                PathsPromoteRequest request = new PathsPromoteRequest(
                        sourceTargetPaths.getSource(),
                        sourceTargetPaths.getTarget(),
                        sourceTargetPaths.getPaths());
                doPromoteByPath(request, !tempBuild, false);
            } catch (RepositoryDriverException | PromotionValidationException ex) {
                userLog.error("Built artifact promotion failed. Error(s): {}", ex.getMessage());
                throw ex;
            }
        }
    }

    /**
     * Promotes a set of artifact paths (or everything, if the path-set is missing) from a particular Indy artifact
     * store to another, and handle the various error conditions that may arise. If the promote call fails, attempt to
     * rollback before throwing an exception.
     *
     * @param req The promotion request to process, which contains source and target store keys, and (optionally) the
     *        set of paths to promote
     * @param setTargetRO flag telling if the target repo should be set to readOnly
     * @param setSourceRO flag telling if the source repo should be set to readOnly
     * @throws RepositoryDriverException when the client API throws an exception due to something unexpected in
     *         transport
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     */
    private void doPromoteByPath(PathsPromoteRequest req, boolean setSourceRO, boolean setTargetRO)
            throws RepositoryDriverException, PromotionValidationException {
        IndyPromoteClientModule promoter;
        try {
            promoter = indy.module(IndyPromoteClientModule.class);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy promote client module. Reason: %s",
                    e,
                    e.getMessage());
        }

        try {
            PathsPromoteResult result = promoter.promoteByPath(req);
            if (result.succeeded()) {
                if (setSourceRO) {
                    setHostedReadOnly(req.getSource(), promoter, result);
                }
                if (setTargetRO) {
                    setHostedReadOnly(req.getTarget(), promoter, result);
                }
            } else {
                String error = getValidationError(result);
                throw new PromotionValidationException("Failed to promote: %s. Reason given was: %s", req, error);
            }
        } catch (IndyClientException e) {
            throw new RepositoryDriverException("Failed to promote: %s. Reason: %s", e, req, e.getMessage());
        }
    }

    /**
     * Sets readonly flag on a hosted repo after promotion. If it fails, it rolls back the promotion and throws
     * RepositoryManagerException.
     *
     * @param key the hosted repo key to be set readonly
     * @param promoter promote client module used for potential rollback
     * @param result the promotion result used for potential rollback
     * @throws IndyClientException in case the repo data cannot be loaded
     * @throws RepositoryDriverException in case the repo update fails
     */
    private void setHostedReadOnly(StoreKey key, IndyPromoteClientModule promoter, PathsPromoteResult result)
            throws IndyClientException, RepositoryDriverException {
        HostedRepository hosted = indy.stores().load(key, HostedRepository.class);
        hosted.setReadonly(true);
        try {
            indy.stores().update(hosted, "Setting readonly after successful build and promotion.");
        } catch (IndyClientException ex) {
            try {
                promoter.rollbackPathPromote(result);
            } catch (IndyClientException ex2) {
                logger.error(
                        "Failed to set readonly flag on repo: {}. Reason given was: {}.",
                        key,
                        ex.getMessage(),
                        ex);
                throw new RepositoryDriverException(
                        "Subsequently also failed to rollback the promotion of paths from %s to %s. Reason "
                                + "given was: %s",
                        ex2,
                        result.getRequest().getSource(),
                        result.getRequest().getTarget(),
                        ex2.getMessage());
            }
            throw new RepositoryDriverException(
                    "Failed to set readonly flag on repo: %s. Reason given was: %s",
                    ex,
                    key,
                    ex.getMessage());
        }
    }

    /**
     * Computes error message from a failed promotion result. It means either error must not be empty or validations
     * need to contain at least 1 validation error.
     *
     * @param result the promotion result
     * @return the error message
     */
    private String getValidationError(AbstractPromoteResult<?> result) {
        StringBuilder sb = new StringBuilder();
        String errorMsg = result.getError();
        ValidationResult validations = result.getValidations();
        if (errorMsg != null) {
            sb.append(errorMsg);
            if (validations != null) {
                sb.append("\n");
            }
        }
        if ((validations != null) && (validations.getRuleSet() != null)) {
            sb.append("One or more validation rules failed in rule-set ")
                    .append(validations.getRuleSet())
                    .append(":\n");

            if (validations.getValidatorErrors().isEmpty()) {
                sb.append("(no validation errors received)");
            } else {
                validations.getValidatorErrors()
                        .forEach(
                                (rule, error) -> sb.append("- ")
                                        .append(rule)
                                        .append(":\n")
                                        .append(error)
                                        .append("\n\n"));
            }
        }
        if (sb.length() == 0) {
            sb.append("(no error message received)");
        }
        return sb.toString();
    }

    /**
     * Cleans up the repo group and used generic-http remote repos and groups from Indy. The generic-http remote repos
     * are needed for promotion.
     *
     * The cleanup shouldn't be called if the build failed to leave the group for debugging the build. All the groups
     * are deleted by PNC Cleaner (not part of this driver) after 7 days.
     *
     * @param genericRepos a collection of generic repos containing dependencies
     */
    private void deleteBuildRepos(
            RepositoryType repositoryType,
            String buildContentId,
            Collection<StoreKey> genericRepos) throws RepositoryDriverException {
        try {
            String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);
            StoreKey key = new StoreKey(packageType, StoreType.group, buildContentId);
            IndyStoresClientModule indyStores = indy.stores();
            indyStores.delete(key, "[Post-Build] Removing build aggregation group: " + buildContentId);

            for (StoreKey genericRepo : genericRepos) {
                StoreKey other = null;
                if (genericRepo.getType() == StoreType.group) {
                    String remoteName = getGenericRemoteName(genericRepo.getName());
                    if (remoteName != null) {
                        other = new StoreKey(genericRepo.getPackageType(), StoreType.remote, remoteName);
                    }
                } else if (genericRepo.getType() == StoreType.remote) {
                    String groupName = getGenericGroupName(genericRepo.getName());
                    if (groupName != null) {
                        other = new StoreKey(genericRepo.getPackageType(), StoreType.group, groupName);
                    }
                } else {
                    logger.error("Unexpected store type in " + genericRepo + " which should be cleaned. Skipping.");
                }

                if (other != null) {
                    indyStores.delete(
                            genericRepo,
                            "[Post-Build] Removing generic http " + genericRepo.getType() + ": "
                                    + genericRepo.getName());
                    indyStores.delete(
                            other,
                            "[Post-Build] Removing generic http " + other.getType() + ": " + other.getName());
                }
            }
        } catch (IndyClientException e) {
            throw new RepositoryDriverException("Failed to retrieve Indy stores module. Reason: %s", e, e.getMessage());
        }
    }

    /**
     * For a remote generic http repo/group computes matching hosted repo name.
     *
     * @param remoteName the remote repo name
     * @return computed hosted repo name
     */
    private String getGenericGroupName(String remoteName) {
        String groupName;
        if (remoteName.startsWith("r-")) {
            groupName = "g-" + remoteName.substring(2);
        } else {
            logger.error("Unexpected generic http remote repo name {}. Cannot convert it to a group name.", remoteName);
            groupName = null;
        }
        return groupName;
    }

    /**
     * For a generic group computes matching remote repo name.
     *
     * @param groupName the group name
     * @return computed remote repo name
     */
    private String getGenericRemoteName(String groupName) {
        String remoteName;
        if (groupName.startsWith("g-")) {
            remoteName = "r-" + groupName.substring(2);
        } else {
            logger.error("Unexpected generic http group name {}. Cannot convert it to a remote repo name.", groupName);
            remoteName = null;
        }
        return remoteName;
    }

    private Runnable heartBeatSender(Request heartBeat) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(heartBeat.getUri())
                .method(heartBeat.getMethod().name(), HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()));
        heartBeat.getHeaders().forEach(h -> builder.header(h.getName(), h.getValue()));
        HttpRequest request = builder.build();
        return () -> {
            CompletableFuture<HttpResponse<String>> response = httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString());
            response.handleAsync(Context.current().wrapFunction((r, t) -> {
                if (t != null) {
                    logger.warn("Failed to send heartbeat.", t);
                } else {
                    logger.debug("Heartbeat sent. Response status: {}", r.statusCode());
                }
                return null;
            }), executor);
        };
    }

    @WithSpan()
    public void sealTrackingReport(@SpanAttribute(value = "buildContentId") String buildContentId)
            throws RepositoryDriverException {
        IndyFoloAdminClientModule foloAdmin;
        try {
            foloAdmin = indy.module(IndyFoloAdminClientModule.class);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy client module for the artifact tracker: %s",
                    e,
                    e.getMessage());
        }

        try {
            userLog.info("Sealing tracking record");
            boolean sealed = foloAdmin.sealTrackingRecord(buildContentId);
            if (!sealed) {
                throw new RepositoryDriverException("Failed to seal content-tracking record for: %s.", buildContentId);
            }
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to seal tracking report for: %s. Reason: %s",
                    e,
                    buildContentId,
                    e.getMessage());
        }
    }

    private TrackedContentDTO retrieveTrackingReport(String buildContentId) throws RepositoryDriverException {
        IndyFoloAdminClientModule foloAdmin;
        try {
            foloAdmin = indy.module(IndyFoloAdminClientModule.class);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy client module for the artifact tracker: %s",
                    e,
                    e.getMessage());
        }

        TrackedContentDTO report;
        try {
            userLog.info("Getting tracking report");
            report = foloAdmin.getTrackingReport(buildContentId);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve tracking report for: %s. Reason: %s",
                    e,
                    buildContentId,
                    e.getMessage());
        }
        if (report == null) {
            throw new RepositoryDriverException("Failed to retrieve tracking report for: %s.", buildContentId);
        }
        return report;
    }

    private Function<HttpResponse<String>, HttpResponse<String>> validateResponse() {
        return response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response;
            } else {
                throw new FailedResponseException("Response status code: " + response.statusCode());
            }
        };
    }
}
