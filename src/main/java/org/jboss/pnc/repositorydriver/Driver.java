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

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpClient.Version.HTTP_2;
import static org.jboss.pnc.api.constants.HttpHeaders.AUTHORIZATION_STRING;
import static org.jboss.pnc.api.constants.HttpHeaders.CONTENT_TYPE_STRING;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.rest.client.inject.RestClient;
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
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.bifrost.upload.BifrostUploadException;
import org.jboss.pnc.bifrost.upload.LogMetadata;
import org.jboss.pnc.bifrost.upload.TagOption;
import org.jboss.pnc.common.log.MDCUtils;
import org.jboss.pnc.common.otel.OtelUtils;
import org.jboss.pnc.quarkus.client.auth.runtime.PNCClientAuth;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.group.ArtifactoryBuildGroupBuilder;
import org.jboss.pnc.repositorydriver.rest.TrackingServiceClient;
import org.jboss.pnc.repositorydriver.runtime.ApplicationLifecycle;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.RepositoryHandle;
import org.jfrog.artifactory.client.model.Repository;
import org.jfrog.artifactory.client.model.repository.PomCleanupPolicy;
import org.jfrog.artifactory.client.model.repository.settings.RepositorySettings;
import org.jfrog.artifactory.client.model.repository.settings.impl.MavenRepositorySettingsImpl;
import org.jfrog.artifactory.client.model.repository.settings.impl.NpmRepositorySettingsImpl;
import org.jfrog.build.api.Build;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.event.ExecutionAttemptedEvent;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@RequestScoped
public class Driver {

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);
    private static final Logger userLog = LoggerFactory.getLogger("org.jboss.pnc._userlog_.repository-driver");

    @Inject
    ArtifactFilterDatabase artifactFilterDatabase;

    @Inject
    ManagedExecutor executor;

    @Inject
    Configuration configuration;

    @Inject
    java.net.http.HttpClient httpClient;

    @Inject
    ObjectMapper jsonMapper;

    @Inject
    ApplicationLifecycle lifecycle;

    @Inject
    TrackingReportProcessor trackingReportProcessor;

    @Inject
    PNCClientAuth pncClientAuth;

    @Inject
    BifrostLogUploader bifrostLogUploader;

    @Inject
    Artifactory artifactory;

    @Inject
    @RestClient
    TrackingServiceClient trackingServiceClient;

    @WithSpan()
    public RepositoryCreateResponse create(
            @SpanAttribute(value = "repositoryCreateRequest") RepositoryCreateRequest repositoryCreateRequest)
            throws RepositoryDriverException {
        try {
            BuildType buildType = repositoryCreateRequest.getBuildType();
            PackageType packageType = TypeConverters.toPackageType(buildType.getRepoType());
            String buildId = repositoryCreateRequest.getBuildContentId();

            // Calculate repository names once
            String hostedRepoName = ArtifactoryUtils.createRepositoryName(
                    configuration.getNamingStructure(),
                    configuration.getDeploymentType().toString(),
                    buildType,
                    repositoryCreateRequest.isTempBuild(),
                    buildId);
            String virtualRepoName = ArtifactoryUtils.createRepositoryName(
                    configuration.getNamingStructure(),
                    configuration.getDeploymentType().toString(),
                    buildType,
                    repositoryCreateRequest.isTempBuild(),
                    buildId + "-virt");

            setupBuildRepos(
                    hostedRepoName,
                    virtualRepoName,
                    buildId,
                    buildType,
                    repositoryCreateRequest.getBuildCategory(),
                    packageType,
                    repositoryCreateRequest.isTempBuild(),
                    repositoryCreateRequest.getExtraRepositories());

            String downloadsUrl;
            String deployUrl;

            trackingServiceClient.clearReport(buildId);
            trackingServiceClient.initReport(buildId);

            // TODO: This assumes artifactoryUrl always has a '/' at the end.
            deployUrl = configuration.artifactoryUrl + hostedRepoName;
            downloadsUrl = configuration.artifactoryUrl + virtualRepoName;

            // TODO: With Artifactory will we need the sidecar translation?
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
            logger.info("Using '{}' for deployment build: {}", deployUrl, buildId);

            uploadLogs("", "create");
            return new RepositoryCreateResponse(
                    downloadsUrl,
                    deployUrl,
                    configuration.isSidecarEnabled(),
                    configuration.isSidecarArchiveEnabled());
        } catch (Exception ex) {
            userLog.error(ex.getMessage());
            uploadLogs(ex.getMessage(), "create");
            throw ex;
        }
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
        BuildCategory buildCategory = promoteRequest.getBuildCategory();
        TrackingReport report;
        try {
            logger.warn("### Retrieving tracking report");
            report = retrieveTrackingReport(buildContentId);
        } catch (RepositoryDriverException ex) {
            userLog.error(ex.getMessage());
            uploadLogs(ex.getMessage(), "promote");
            throw ex;
        }
        Set<RepositoryKey> genericRepos = new HashSet<>();

        logger.warn("### About to run async");
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

            final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

            try {
                scheduler.scheduleAtFixedRate(
                        heartBeatSender,
                        0,
                        configuration.getHeartbeatInterval(),
                        TimeUnit.SECONDS);

                try {
                    downloadedArtifacts = trackingReportProcessor
                            .collectDownloadedArtifacts(report, artifactFilterDatabase);
                    uploadedArtifacts = trackingReportProcessor.collectUploadedArtifacts(
                            report,
                            promoteRequest.isTempBuild(),
                            buildCategory);
                } catch (RepositoryDriverException e) {
                    String message = "Failed collecting downloaded or uploaded artifacts: ";
                    userLog.error(message, e);
                    uploadLogs(message + e.getMessage(), "promote");
                    notifyInvoker(
                            promoteRequest.getCallback(),
                            RepositoryPromoteResult.failed(buildContentId, ResultStatus.SYSTEM_ERROR));
                    return;
                }

                try {
                    // the promotion is done only after a successfully collected downloads and uploads
                    // Use BuildInfo-based promotion instead of path-based promotion
                    Map<RepositoryKey, org.jfrog.build.api.Build> buildInfos = trackingReportProcessor
                            .createPromotionBuildInfos(
                                    report,
                                    promoteRequest.isTempBuild(),
                                    buildContentId,
                                    buildType.getRepoType(),
                                    buildCategory,
                                    genericRepos);

                    // Promote each BuildInfo to its target repository
                    for (Map.Entry<RepositoryKey, org.jfrog.build.api.Build> entry : buildInfos.entrySet()) {
                        RepositoryKey targetRepo = entry.getKey();
                        org.jfrog.build.api.Build buildInfo = entry.getValue();

                        // Determine if this is artifacts or dependencies based on module content
                        boolean hasArtifacts = buildInfo.getModules().get(0).getArtifacts() != null
                                && !buildInfo.getModules().get(0).getArtifacts().isEmpty();
                        boolean hasDependencies = buildInfo.getModules().get(0).getDependencies() != null
                                && !buildInfo.getModules().get(0).getDependencies().isEmpty();

                        // Promote artifacts if present
                        if (hasArtifacts) {
                            logger.info(
                                    "Promoting artifacts for BuildInfo {} to {}",
                                    buildInfo.getName(),
                                    targetRepo.repositoryId().getName());
                            promoteBuildInfo(targetRepo, buildInfo, true);
                        }

                        // Promote dependencies if present
                        if (hasDependencies) {
                            logger.info(
                                    "Promoting dependencies for BuildInfo {} to {}",
                                    buildInfo.getName(),
                                    targetRepo.repositoryId().getName());
                            promoteBuildInfo(targetRepo, buildInfo, false);
                        }
                    }
                } catch (RepositoryDriverException e) {
                    String message = "Failed promoting downloaded or uploaded artifacts: ";
                    userLog.error(message, e);
                    uploadLogs(message + e.getMessage(), "promote");
                    notifyInvoker(
                            promoteRequest.getCallback(),
                            RepositoryPromoteResult.failed(buildContentId, ResultStatus.SYSTEM_ERROR));
                    return;
                } catch (PromotionValidationException e) {
                    String message = "Failed promoting downloaded or uploaded artifacts: ";
                    userLog.warn(message, e);
                    uploadLogs(message + e.getMessage(), "promote");
                    notifyInvoker(
                            promoteRequest.getCallback(),
                            RepositoryPromoteResult.failed(buildContentId, ResultStatus.FAILED));
                    return;
                }
            } finally {
                shutdownScheduler(scheduler);
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

            uploadLogs("", "promote");
            notifyInvoker(
                    promoteRequest.getCallback(),
                    new RepositoryPromoteResult(
                            uploadedArtifacts,
                            downloadedArtifacts,
                            buildContentId,
                            ResultStatus.SUCCESS));
        })).thenRunAsync(Context.current().wrap(() -> {
            // CLEANUP
            try {
                logger.info(
                        "Deleting build group {} {} and the generic http {} repositories...",
                        buildType.getRepoType(),
                        buildContentId,
                        genericRepos);
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

                        // Create a parent child span with values from MDC
                        SpanBuilder spanBuilder = OtelUtils.buildChildSpan(
                                GlobalOpenTelemetry.get().getTracer(""),
                                "Driver.archive",
                                SpanKind.CLIENT,
                                MDC.get(MDCKeys.TRACE_ID_KEY),
                                MDC.get(MDCKeys.SPAN_ID_KEY),
                                MDC.get(MDCKeys.TRACE_FLAGS_KEY),
                                MDC.get(MDCKeys.TRACE_STATE_KEY),
                                Span.current().getSpanContext(),
                                Map.of("buildContentId", buildContentId, "buildConfigId", buildConfigurationId));
                        Span span = spanBuilder.startSpan();
                        logger.debug("Started a new span :{}", span);

                        // put the span into the current Context
                        try (Scope scope = span.makeCurrent()) {
                            archive(archiveRequest);
                        } finally {
                            span.end(); // closing the scope does not end the span, this has to be done manually
                        }

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

    private void uploadLogs(String message, String operation) {
        if (!configuration.bifrostUploaderEnabled) {
            logger.warn("Bifrost uploader is not enabled for message {} with operation {}", message, operation);
            return;
        }
        try {
            LogMetadata logMetadata = LogMetadata.builder()
                    .headers(MDCUtils.getHeadersFromMDC())
                    .loggerName("org.jboss.pnc._userlog_.repository-driver." + operation)
                    .tag(TagOption.BUILD_LOG)
                    .endTime(OffsetDateTime.now())
                    .build();
            bifrostLogUploader.uploadString(message, logMetadata);
        } catch (BifrostUploadException ex) {
            logger.error("Unable to upload logs to bifrost. Log was: \n{}", message, ex);
            // We don't want to fail the build when we couldn't upload to bifrost, because the repo driver log is not
            // critical.
        }
    }

    @WithSpan()
    public void archive(@SpanAttribute(value = "archiveRequest") ArchiveRequest request)
            throws RepositoryDriverException {
        // TODO: ### Eventually evaluate whether we need the service
        if (configuration.archiveServiceEnabled) {
            TrackingReport report = retrieveTrackingReport(request.getBuildContentId());
            doArchive(request, report);
        } else {
            logger.warn("Archive service disabled");
        }
    }

    private void doArchive(
            @SpanAttribute(value = "archiveRequest") ArchiveRequest request,
            @SpanAttribute(value = "report") TrackingReport report) throws RepositoryDriverException {

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
        final String body;
        String body1;
        try {
            body1 = jsonMapper.writeValueAsString(archivePayload);
        } catch (JsonProcessingException e) {
            logger.error("Cannot serialize callback object.", e);
            body1 = "";
        }

        body = body1;
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

        logger.info("About to call archival service {}.", configuration.getArchiveServiceEndpoint());
        return Failsafe.with(retryPolicy)
                .with(executor)
                .getStageAsync(
                        () -> httpClient.sendAsync(getArchivalHttpRequest(body), HttpResponse.BodyHandlers.ofString())
                                .thenApply(validateResponse()))
                .join();
    }

    /**
     * Build the archival http request with authentication headers
     *
     * @param body body of request
     * @return HttpRequest
     */
    private HttpRequest getArchivalHttpRequest(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .version(configuration.isArchiveServicePreferHttp2() ? HTTP_2 : HTTP_1_1)
                .uri(URI.create(configuration.getArchiveServiceEndpoint()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()))
                .header(AUTHORIZATION_STRING, pncClientAuth.getHttpAuthorizationHeaderValue())
                .header(CONTENT_TYPE_STRING, "application/json");

        return builder.build();
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

    /**
     * Send the data to the requestor via the callback url
     *
     * @param callback
     * @param promoteResult
     */
    private void notifyInvoker(Request callback, RepositoryPromoteResult promoteResult) {
        final String body;
        String body1;
        try {
            body1 = jsonMapper.writeValueAsString(promoteResult);
        } catch (JsonProcessingException e) {
            logger.error("Cannot serialize callback object.", e);
            body1 = "";
        }
        body = body1;

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
                        () -> httpClient
                                .sendAsync(getNotifyHttpRequest(callback, body), HttpResponse.BodyHandlers.ofString())
                                .thenApply(validateResponse()))
                .handle(Context.current().wrapFunction((r, t) -> {
                    lifecycle.removeActivePromotion();
                    return null;
                }));
    }

    private HttpRequest getNotifyHttpRequest(Request callback, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(callback.getUri())
                .method(callback.getMethod().name(), HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()));
        callback.getHeaders().forEach(h -> builder.header(h.getName(), h.getValue()));
        // Add the service account's access token. We use a fresh one instead of serviceTokens since serviceTokens might
        // already be closed to expiry when we hit this method inside the executor
        builder.header(jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION, pncClientAuth.getHttpAuthorizationHeaderValue());
        return builder.build();
    }

    @WithSpan()
    public RepositoryPromoteResult collectRepoManagerResult(
            @SpanAttribute(value = "buildContentId") String buildContentId,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "buildCategory") BuildCategory buildCategory) throws RepositoryDriverException {
        TrackingReport report = retrieveTrackingReport(buildContentId);
        try {
            List<RepositoryArtifact> downloadedArtifacts = trackingReportProcessor
                    .collectDownloadedArtifacts(report, artifactFilterDatabase);
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
                    ResultStatus.SUCCESS);
        } catch (RepositoryDriverException e) {
            String message = e.getMessage();
            userLog.error("Failed to collect artifacts. Error(s): {}", message);
            return new RepositoryPromoteResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    buildContentId,
                    ResultStatus.FAILED);
        }
    }

    /**
     * Create the hosted repository and group necessary to support a single build. The hosted repository holds artifacts
     * uploaded from the build, and the group coordinates access to this hosted repository, along with content from the
     * product-level content group with which this build is associated. The group also provides a tracking target, so
     * the repository manager can keep track of downloads and uploads for the build.
     *
     * @param hostedName the name of the hosted repository
     * @param virtualName the name of the virtual/group repository
     * @param buildContentId the build content ID for logging
     * @param packageType the package type key used by Indy
     */
    private void setupBuildRepos(
            String hostedName,
            String virtualName,
            String buildContentId,
            BuildType buildType,
            BuildCategory buildCategory,
            PackageType packageType,
            boolean tempBuild,
            List<String> extraDependencyRepositories) throws RepositoryDriverException {

        try {
            // Was using try/resources but now switched to injected artifactory for tests
            // (Artifactory artifactory = createArtifactoryClient()) {
            logger.info("### setupBuildRepos::hostedName: {}, virtualName: {}", hostedName, virtualName);
            // Check repositories exist and delete if they do
            RepositoryHandle hostedRepository = artifactory.repository(hostedName);
            RepositoryHandle virtualRepository = artifactory.repository(virtualName);
            // Under the hood this uses https://jfrog.com/help/r/jfrog-rest-apis/get-repository-configuration
            // which will fail with "This REST API is available only in Artifactory Pro" if we're using OSS version.
            if (hostedRepository.exists()) {
                hostedRepository.delete();
            }
            if (virtualRepository.exists()) {
                virtualRepository.delete();
            }

            RepositorySettings settings = null;
            switch (packageType) {
                case MAVEN: {
                    // Create local and virtual repository
                    // MavenRepositorySettingsImpl implicitly sets package type maven.
                    settings = new MavenRepositorySettingsImpl();
                    // https://jfrog.com/help/r/jfrog-artifactory-documentation/additional-settings-for-maven/gradle/ivy/sbt-local-repositories
                    // TODO: Should we disable this? It verifies that the value set for
                    //       groupId:artifactId:version in the POM is consistent with the deployed path.
                    ((MavenRepositorySettingsImpl) settings).setSuppressPomConsistencyChecks(true);
                    ((MavenRepositorySettingsImpl) settings).setHandleReleases(true);
                    ((MavenRepositorySettingsImpl) settings).setHandleSnapshots(false);
                    // Don't alter repository references in the poms.
                    ((MavenRepositorySettingsImpl) settings).setPomRepositoryReferencesCleanupPolicy(
                            PomCleanupPolicy.nothing);
                    // Don't need this as we are disabling snapshots
                    // ((MavenRepositorySettingsImpl) settings).setSnapshotVersionBehavior(SnapshotVersionBehaviorImpl.unique);
                    break;
                }
                case NPM: {
                    settings = new NpmRepositorySettingsImpl();
                }
                // TODO: Will we need to support other types here?
            }

            var repository = artifactory.repositories()
                    .builders()
                    .localRepositoryBuilder()
                    .archiveBrowsingEnabled(true)
                    .projectKey(configuration.getDeploymentType().toString())
                    .environments(Collections.singletonList(configuration.getEnvironment()))
                    .description("PNC Build repository for " + hostedName)
                    .repositorySettings(settings)
                    .key(hostedName)
                    .build();
            // TODO: What is the position. Undocumented in the REST API. Comes through as "?pos="
            String r = artifactory.repositories().create(1, repository);

            logger.info(
                    "### setupBuildRepos::created local repo: {} extraDependencyRepos {}",
                    r,
                    extraDependencyRepositories);

            Repository group = ArtifactoryBuildGroupBuilder
                    .builder(configuration, artifactory, settings, virtualName)
                    .withDescription(
                            String.format(
                                    "Aggregation group for PNC %s build #%s",
                                    tempBuild ? "temporary " : "",
                                    buildContentId))
                    // build-local artifacts
                    .addConstituent(hostedName)
                    // Global-level repos, for captured/shared artifacts and access to the outside world
                    .addGlobalConstituents(buildType, buildCategory, tempBuild)
                    // build-specific repos
                    .addExtraConstituents(extraDependencyRepositories)
                    .build();
            String changelog = "Creating repository group for resolving artifacts (repo: " + buildContentId
                    + "), with tempBuild: " + tempBuild;
            logger.info(changelog);
            r = artifactory.repositories().create(1, group);
            logger.info("### setupBuildRepos::created virtual repo: {}", r);

        } catch (Exception e) {
            logger.error("### Caught exception", e);
            throw new RepositoryDriverException("Error setting up build repositories", e);
        }
    }

    /**
     * Promotes by path downloads captured in given map. The key in the map is promotion target store key. The value is
     * another map, where key is promotion source store key and value is list of paths to be promoted.
     *
     * @throws RepositoryDriverException in case of an unexpected error during promotion
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     *         private void promoteDownloads(PromotionPaths promotionPaths, boolean tempBuild, String
     *         promotionTrackingId)
     *         throws RepositoryDriverException, PromotionValidationException {
     *         // Promote all build dependencies NOT ALREADY CAPTURED to the hosted repository holding store for the
     *         shared
     *         // imports
     *         for (SourceTargetPaths sourceTargetPaths : promotionPaths.getSourceTargetsPaths()) {
     *         // set read-only only the generic http proxy hosted repos, not shared-imports
     *         boolean readonly = !tempBuild && GENERIC.equals(sourceTargetPaths.getTarget().packageType());
     *
     *         userLog.info(
     *         "Promoting {} dependencies from {} to {}",
     *         sourceTargetPaths.getPaths().size(),
     *         sourceTargetPaths.getSource(),
     *         sourceTargetPaths.getTarget());
     *
     *         artifactoryPromoteByPath(sourceTargetPaths, false, readonly);
     *         }
     *         }
     *
     *         // TODO: ### Do need the readonly markers?
     *         private void artifactoryPromoteByPath(SourceTargetPaths sourceTargetPaths, boolean b, boolean readonly) {
     *         // TODO: ### For now assuming RepositoryKey::repositoryId is the repository name
     *         // TODO: Handling temp and virtual flags
     *
     *         // Convert PackageType enum to string for ArtifactoryUtils
     *         String sourcePackageTypeStr = sourceTargetPaths.getSource().packageType().name().toLowerCase();
     *         String targetPackageTypeStr = sourceTargetPaths.getTarget().packageType().name().toLowerCase();
     *
     *         // String sourceRepository = ArtifactoryUtils.createRepositoryName(
     *         // configuration.getNamingStructure(),
     *         // configuration.getDeploymentType().toString(),
     *         // ArtifactoryUtils.parsePackageType(sourcePackageTypeStr),
     *         // false,
     *         // false,
     *         // sourceTargetPaths.getSource().getRepositoryId().getName());
     *         // String targetRepository = ArtifactoryUtils.createRepositoryName(
     *         // configuration.getNamingStructure(),
     *         // configuration.getDeploymentType().toString(),
     *         // ArtifactoryUtils.parsePackageType(targetPackageTypeStr),
     *         // false,
     *         // false,
     *         // sourceTargetPaths.getTarget().getRepositoryId().getName());
     *         logger.info(
     *         "### Looking for source ID {} and package type {} source repository {} target repository {}",
     *         sourceTargetPaths.getSource().repositoryId(),
     *         sourceTargetPaths.getSource().packageType(),
     *         sourceTargetPaths.getSource().repositoryId(),
     *         sourceTargetPaths.getTarget().repositoryId());
     *         RepositoryHandle handle = artifactory.repository(sourceTargetPaths.getSource().repositoryId().getName());
     *         logger.warn("### Got handle {}", handle.getClass().getName());
     *         // Under the hood this uses https://jfrog.com/help/r/jfrog-rest-apis/get-repository-configuration
     *         // which will fail with "This REST API is available only in Artifactory Pro" if we're using OSS version.
     *         if (!handle.exists()) {
     *         throw new RuntimeException(
     *         "Unable to find source repository " + sourceTargetPaths.getSource().repositoryId().getName());
     *         }
     *         if (!artifactory.repository(sourceTargetPaths.getTarget().repositoryId().getName()).exists()) {
     *         throw new RuntimeException(
     *         "Unable to find target repository " + sourceTargetPaths.getTarget().repositoryId().getName());
     *         }
     *
     *         List<String> copied = new ArrayList<>();
     *         for (String path : sourceTargetPaths.getPaths()) {
     *         try {
     *         // Where should we promote to?
     *         handle.folder(path).copy(sourceTargetPaths.getTarget().repositoryId().getName(), path);
     *         copied.add(path);
     *         } catch (CopyMoveException e) {
     *         logger.error("Caught exception promoting {}", path, e);
     *         logger.warn("Copied {} so far; removing from promotion", copied);
     *         // TODO: ### Should we remove what we have copied so far?
     *         RepositoryHandle cleanup = artifactory
     *         .repository(sourceTargetPaths.getTarget().repositoryId().getName());
     *         copied.forEach(cleanup::delete);
     *         }
     *         }
     *         // TODO: Cleanup and set repositories to readonly. While changing maven repositories
     *         // not to handle release or snapshot deploymentType might work not sure about npm or generic repos
     *         }
     */

    /**
     * Promote the build output to the consolidated build repo (using path promotion, where the build repo contents are
     * added to the repo's contents) and marks the build output as readonly.
     *
     * @throws RepositoryDriverException when the repository client API throws an exception due to something unexpected
     *         in transport
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     *         private void promoteUploads(PromotionPaths promotionPaths, boolean tempBuild, String promotionTrackingID)
     *         throws RepositoryDriverException, PromotionValidationException {
     *         for (SourceTargetPaths sourceTargetPaths : promotionPaths.getSourceTargetsPaths()) {
     *         userLog.info(
     *         "Promoting {} build output from {} to {}",
     *         sourceTargetPaths.getPaths().size(),
     *         sourceTargetPaths.getSource(),
     *         sourceTargetPaths.getTarget());
     *
     *         artifactoryPromoteByPath(sourceTargetPaths, false, false);
     *         }
     *         }
     */

    /**
     * Promotes a BuildInfo to a target repository using Artifactory's Build API. This method uploads the BuildInfo
     * metadata and then promotes it to the target repository.
     *
     * <p>
     * This replaces the path-based promotion approach with BuildInfo-based promotion, which provides better
     * traceability and atomic operations.
     * </p>
     *
     * @param targetRepo the target repository key
     * @param buildInfo the BuildInfo object containing artifacts and dependencies
     * @param promoteArtifacts true to promote artifacts (uploads), false to promote dependencies (downloads)
     * @throws PromotionValidationException if upload or promotion fails
     */
    private void promoteBuildInfo(
            RepositoryKey targetRepo,
            Build buildInfo,
            boolean promoteArtifacts) throws PromotionValidationException {

        String buildName = buildInfo.getName();
        String buildNumber = buildInfo.getNumber();
        String targetRepoName = targetRepo.repositoryId().getName();
        String scope = promoteArtifacts ? "artifacts" : "dependencies";

        try {
            // Step 1: Upload BuildInfo to Artifactory
            logger.info("Uploading BuildInfo {} #{} to Artifactory", buildName, buildNumber);
            artifactory.builds().uploadBuild(buildInfo);
            userLog.info(
                    "Uploaded BuildInfo {} #{} with {} artifacts and {} dependencies",
                    buildName,
                    buildNumber,
                    buildInfo.getModules().get(0).getArtifacts() != null
                            ? buildInfo.getModules().get(0).getArtifacts().size()
                            : 0,
                    buildInfo.getModules().get(0).getDependencies() != null
                            ? buildInfo.getModules().get(0).getDependencies().size()
                            : 0);

        } catch (Exception e) {
            String message = String.format(
                    "Failed to upload BuildInfo %s #%s to Artifactory",
                    buildName,
                    buildNumber);
            logger.error(message, e);
            throw new PromotionValidationException(message, e);
        }

        try {
            // Step 2: Create BuildPromotionRequest using concrete implementation
            org.jfrog.artifactory.client.model.impl.BuildPromotionRequestImpl promotionRequest = new org.jfrog.artifactory.client.model.impl.BuildPromotionRequestImpl();

            promotionRequest.setTargetRepo(targetRepoName);
            promotionRequest.setStatus("promoted");
            promotionRequest.setComment("Promoted by PNC Repository Driver - " + scope);
            promotionRequest.setCopy(false); // Move artifacts, don't copy them

            // Set flags for what to promote: artifacts (uploads) or dependencies (downloads)
            promotionRequest.setArtifacts(promoteArtifacts);
            promotionRequest.setDependencies(!promoteArtifacts);

            // Step 3: Promote the build
            logger.info(
                    "Promoting BuildInfo {} #{} ({}) to repository {}",
                    buildName,
                    buildNumber,
                    scope,
                    targetRepoName);
            artifactory.builds().promoteBuild(buildName, buildNumber, promotionRequest);

            userLog.info(
                    "Successfully promoted BuildInfo {} #{} ({}) to {}",
                    buildName,
                    buildNumber,
                    scope,
                    targetRepoName);

        } catch (Exception e) {
            String message = String.format(
                    "Failed to promote BuildInfo %s #%s (%s) to repository %s",
                    buildName,
                    buildNumber,
                    scope,
                    targetRepoName);
            logger.error(message, e);
            throw new PromotionValidationException(message, e);
        }

        // TODO: Cleanup and set repositories to readonly. While changing maven repositories
        //     not to handle release or snapshot deploymentType might work not sure about npm or generic repos
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
     *         private void setHostedReadOnly(StoreKey key, IndyPromoteClientModule promoter, PathsPromoteResult result)
     *         throws IndyClientException, RepositoryDriverException {
     *         HostedRepository hosted = indy.stores().load(key, HostedRepository.class);
     *         hosted.setReadonly(true);
     *         try {
     *         indy.stores().update(hosted, "Setting readonly after successful build and promotion.");
     *         } catch (IndyClientException ex) {
     *         try {
     *         promoter.rollbackPathPromote(result);
     *         } catch (IndyClientException ex2) {
     *         logger.error(
     *         "Failed to set readonly flag on repo: {}. Reason given was: {}.",
     *         key,
     *         ex.getMessage(),
     *         ex);
     *         throw new RepositoryDriverException(
     *         "Subsequently also failed to rollback the promotion of paths from %s to %s. Reason "
     *         + "given was: %s",
     *         ex2,
     *         result.getRequest().getSource(),
     *         result.getRequest().getTarget(),
     *         ex2.getMessage());
     *         }
     *         throw new RepositoryDriverException(
     *         "Failed to set readonly flag on repo: %s. Reason given was: %s",
     *         ex,
     *         key,
     *         ex.getMessage());
     *         }
     *         }
     */

    /**
     * Computes error message from a failed promotion result. It means either error must not be empty or validations
     * need to contain at least 1 validation error.
     *
     * @param result the promotion result
     * @return the error message
     *         private String getValidationError(AbstractPromoteResult<?> result) {
     *         StringBuilder sb = new StringBuilder();
     *         String errorMsg = result.getError();
     *         ValidationResult validations = result.getValidations();
     *         if (errorMsg != null) {
     *         sb.append(errorMsg);
     *         if (validations != null) {
     *         sb.append("\n");
     *         }
     *         }
     *         if ((validations != null) && (validations.getRuleSet() != null)) {
     *         sb.append("One or more validation rules failed in rule-set ")
     *         .append(validations.getRuleSet())
     *         .append(":\n");
     *
     *         if (validations.getValidatorErrors().isEmpty()) {
     *         sb.append("(no validation errors received)");
     *         } else {
     *         validations.getValidatorErrors()
     *         .forEach(
     *         (rule, error) -> sb.append("- ")
     *         .append(rule)
     *         .append(":\n")
     *         .append(error)
     *         .append("\n\n"));
     *         }
     *         }
     *         if (sb.isEmpty()) {
     *         sb.append("(no error message received)");
     *         }
     *         return sb.toString();
     *         }
     */

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
            Collection<RepositoryKey> genericRepos) throws RepositoryDriverException {
        for (RepositoryKey key : genericRepos) {
            logger.info("Deleting remote build repository {}", key.repositoryId().getPath());
            artifactory.repository(key.repositoryId().getPath()).delete();
        }
    }

    private Runnable heartBeatSender(Request heartBeat) {
        return () -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(heartBeat.getUri())
                    .method(heartBeat.getMethod().name(), HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()));
            heartBeat.getHeaders().forEach(h -> builder.header(h.getName(), h.getValue()));
            builder.header(
                    jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION,
                    pncClientAuth.getHttpAuthorizationHeaderValue());
            HttpRequest request = builder.build();

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

    private void shutdownScheduler(ScheduledExecutorService scheduler) {
        if (scheduler != null) {
            scheduler.shutdown(); // Prevents new tasks, but allows existing tasks to finish
            try {
                // Wait for all tasks to finish or time out
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow(); // Force shutdown if tasks take too long
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow(); // Force shutdown if interrupted
            }
        }
    }

    @WithSpan()
    public void sealTrackingReport(@SpanAttribute(value = "buildContentId") String buildContentId) {
        try {
            userLog.info("Sealing tracking record");
            // TODO: Indy seal returned a boolean - this doesn't?
            trackingServiceClient.sealReport(buildContentId);
            uploadLogs("", "seal");
        } catch (Exception ex) {
            userLog.error(ex.getMessage());
            uploadLogs(ex.getMessage(), "seal");
            throw ex;
        }
    }

    private TrackingReport retrieveTrackingReport(String buildContentId) throws RepositoryDriverException {
        TrackingReport report;
        try {
            userLog.info("Getting tracking report for build: {}", buildContentId);
            report = trackingServiceClient.getReport(buildContentId);
        } catch (Exception e) {
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
