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

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
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
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.repositorydriver.runtime.ApplicationLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@RequestScoped
public class Driver {

    /** Store key of gradle-plugins remote repository. */
    static final String GRADLE_PLUGINS_REPO = "maven:remote:gradle-plugins";

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);
    private static final Logger userLog = LoggerFactory.getLogger("org.jboss.pnc._userlog_.repository-driver");

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

    public RepositoryCreateResponse create(RepositoryCreateRequest repositoryCreateRequest)
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
            indy.module(IndyFoloAdminClientModule.class).initReport(buildId);

            StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildId);
            downloadsUrl = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, groupKey);

            StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildId);
            deployUrl = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, hostedKey);

            logger.info("Using '{}' for {} repository access in build: {}", downloadsUrl, packageType, buildId);
        } catch (IndyClientException e) {
            logger.debug("Failed to retrieve Indy client module for the artifact tracker");
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy client module for the artifact tracker: %s",
                    e,
                    e.getMessage());
        }
        return new RepositoryCreateResponse(downloadsUrl, deployUrl);
    }

    /**
     * Retrieve tracking report from repository manager. Add each tracked download to the dependencies of the build
     * result. Add each tracked upload to the built artifacts of the build result. Promote uploaded artifacts to the
     * product-level storage. Finally delete the group associated with the completed build.
     */
    public void promote(RepositoryPromoteRequest promoteRequest) throws RepositoryDriverException {
        if (lifecycle.isShuttingDown()) {
            throw new StoppingException();
        }
        String buildContentId = promoteRequest.getBuildContentId();
        BuildType buildType = promoteRequest.getBuildType();
        TrackedContentDTO report = retrieveTrackingReport(buildContentId, true);

        // fire and forget
        executor.runAsync(() -> {
            try {
                logger.info("Deleting build group {} {} ...", buildType.getRepoType(), buildContentId);
                deleteBuildGroup(buildType.getRepoType(), buildContentId);
            } catch (Throwable e) {
                logger.error("Failed to delete build group.", e);
            }
        });

        // removeActivePromotion is called as the last step of Driver#notifyInvoker
        lifecycle.addActivePromotion();
        // schedule promotion
        executor.runAsync(() -> {
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
                promoteDownloads(
                        trackingReportProcessor.collectDownloadsPromotions(report),
                        heartBeatSender,
                        promoteRequest.isTempBuild());
                heartBeatSender.run();
                promoteUploads(
                        trackingReportProcessor.collectUploadsPromotions(
                                report,
                                promoteRequest.isTempBuild(),
                                buildType.getRepoType(),
                                buildContentId),
                        promoteRequest.isTempBuild(),
                        heartBeatSender);
            } catch (RepositoryDriverException | PromotionValidationException e) {
                logger.error("Failed promoting downloaded or uploaded artifacts.", e);
                notifyInvoker(
                        promoteRequest.getCallback(),
                        RepositoryPromoteResult.failed(
                                buildContentId,
                                e.getMessage(),
                                e instanceof RepositoryDriverException ? ResultStatus.SYSTEM_ERROR
                                        : ResultStatus.FAILED));
                return;
            }

            logger.info(
                    "Returning built artifacts / dependencies:\nUploads:\n  {}\n\nDownloads:\n  {}\n\n",
                    StringUtils.join(uploadedArtifacts, "\n  "),
                    StringUtils.join(downloadedArtifacts, "\n  "));
            notifyInvoker(
                    promoteRequest.getCallback(),
                    new RepositoryPromoteResult(
                            uploadedArtifacts,
                            downloadedArtifacts,
                            buildContentId,
                            "",
                            ResultStatus.SUCCESS));
        }).handle((nul, throwable) -> {
            if (throwable != null) {
                logger.error("Unhanded promotion exception.", throwable);
            }
            lifecycle.removeActivePromotion();
            return null;
        });
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
                            "Callback retry attempt #{}, last error: [{}], last status: [{}].",
                            ctx.getAttemptCount(),
                            lastError,
                            lastStatus);
                })
                .onFailure(ctx -> logger.error("Unable to send callback."))
                .onAbort(e -> logger.warn("Callback aborted: {}.", e.getFailure().getMessage()));
        Failsafe.with(retryPolicy)
                .with(executor)
                .getStageAsync(
                        () -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .thenApply(validateResponse()))
                .handle((r, t) -> {
                    lifecycle.removeActivePromotion();
                    return null;
                });
    }

    public RepositoryPromoteResult collectRepoManagerResult(
            String buildContentId,
            boolean tempBuild,
            BuildCategory buildCategory) throws RepositoryDriverException {
        TrackedContentDTO report = retrieveTrackingReport(buildContentId, false);
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
            List<String> extraDependencyRepositories) throws IndyClientException {

        // if the build-level group doesn't exist, create it.
        StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildContentId);
        StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildContentId);

        if (!indy.stores().exists(groupKey)) {
            // if the product-level storage repo (for in-progress product builds) doesn't exist, create it.
            if (!indy.stores().exists(hostedKey)) {
                HostedRepository buildArtifacts = new HostedRepository(packageType, buildContentId);
                buildArtifacts.setAllowSnapshots(false);
                buildArtifacts.setAllowReleases(true);

                buildArtifacts.setDescription(
                        String.format("Build output for PNC %s build #%s", packageType, buildContentId));

                indy.stores()
                        .create(
                                buildArtifacts,
                                "Creating hosted repository for " + packageType + " build: " + buildContentId
                                        + " (repo: " + buildContentId + ")",
                                HostedRepository.class);
            }

            Group buildGroup = BuildGroupBuilder.builder(indy, packageType, buildContentId)
                    .withDescription(
                            String.format(
                                    "Aggregation group for PNC %sbuild #%s",
                                    tempBuild ? "temporary " : "",
                                    buildContentId))
                    // build-local artifacts
                    .addConstituent(hostedKey)
                    // Global-level repos, for captured/shared artifacts and access to the outside world
                    .addGlobalConstituents(buildType, tempBuild)
                    .addExtraConstituents(extraDependencyRepositories)
                    .build();

            String changelog = "Creating repository group for resolving artifacts (repo: " + buildContentId + ").";
            indy.stores().create(buildGroup, changelog, Group.class);
        }
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
     * Cleans up the repo group from Indy. The group is not needed for promotion. It shouldn't be done if the build
     * fails, to leave the group for debugging a build. All the groups are deleted by a cleaner(not part of this driver)
     * after 7 days.
     */
    private void deleteBuildGroup(RepositoryType repositoryType, String buildContentId)
            throws RepositoryDriverException {
        try {
            String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);
            StoreKey key = new StoreKey(packageType, StoreType.group, buildContentId);
            indy.stores().delete(key, "[Post-Build] Removing build aggregation group: " + buildContentId);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException("Failed to retrieve Indy stores module. Reason: %s", e, e.getMessage());
        }
    }

    private Runnable heartBeatSender(Request heartBeat) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(heartBeat.getUri())
                .method(heartBeat.getMethod().name(), HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(configuration.getHttpClientRequestTimeout()));
        heartBeat.getHeaders().stream().forEach(h -> builder.header(h.getName(), h.getValue()));
        HttpRequest request = builder.build();
        return () -> {
            CompletableFuture<HttpResponse<String>> response = httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString());
            response.handleAsync((r, t) -> {
                if (t != null) {
                    logger.warn("Failed to send heartbeat.", t);
                } else {
                    logger.debug("Heartbeat sent. Response status: {}", r.statusCode());
                }
                return null;
            }, executor);
        };
    }

    private TrackedContentDTO retrieveTrackingReport(String buildContentId, boolean seal)
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

        TrackedContentDTO report;
        try {
            if (seal) {
                userLog.info("Sealing tracking record");
                boolean sealed = foloAdmin.sealTrackingRecord(buildContentId);
                if (!sealed) {
                    throw new RepositoryDriverException(
                            "Failed to seal content-tracking record for: %s.",
                            buildContentId);
                }
            }
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
