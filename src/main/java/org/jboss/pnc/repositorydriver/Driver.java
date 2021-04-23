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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.commonjava.atlas.npm.ident.util.NpmPackagePathInfo;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.module.IndyContentClientModule;
import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.indy.promote.model.AbstractPromoteResult;
import org.commonjava.indy.promote.model.CallbackTarget;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.indy.promote.model.ValidationResult;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.api.constants.BuildConfigurationParameterKeys;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.constants.ReposiotryIdentifier;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.TargetRepository;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.pnc.enums.BuildType;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.repositorydriver.constants.Checksum;
import org.jboss.pnc.repositorydriver.constants.CompletionStatus;
import org.jboss.pnc.repositorydriver.dto.ArtifactRepository;
import org.jboss.pnc.repositorydriver.dto.CreateRequest;
import org.jboss.pnc.repositorydriver.dto.CreateResponse;
import org.jboss.pnc.repositorydriver.dto.PromoteRequest;
import org.jboss.pnc.repositorydriver.dto.PromoteResult;
import org.jboss.pnc.repositorydriver.runtime.ApplicationLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.COMMON_BUILD_GROUP_CONSTITUENTS_GROUP;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.SHARED_IMPORTS_ID;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.TEMPORARY_BUILDS_GROUP;

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
    Validator validator;

    @Inject
    Indy indy;

    @Inject
    ArtifactFilter artifactFilter;

    @Inject
    ApplicationLifecycle lifecycle;

    public CreateResponse create(CreateRequest createRequest) throws RepositoryDriverException {
        BuildType buildType = createRequest.getBuildType();
        String packageType = TypeConverters.getIndyPackageTypeKey(buildType.getRepoType());
        String buildId = createRequest.getBuildContentId();

        try {
            setupBuildRepos(
                    createRequest.getBuildId(),
                    createRequest.getBuildContentId(),
                    buildType,
                    packageType,
                    createRequest.isTempBuild(),
                    createRequest.getExtraRepositories());
        } catch (IndyClientException e) {
            logger.debug("Failed to setup repository or repository group for this build");
            throw new RepositoryDriverException(
                    "Failed to setup repository or repository group for this build: %s",
                    e,
                    e.getMessage());
        }

        // since we're setting up a group/hosted repo per build, we can pin the tracking ID to the build repo ID.
        String url;
        String deployUrl;

        try {
            // manually initialize the tracking record, just in case (somehow) nothing gets downloaded/uploaded.
            indy.module(IndyFoloAdminClientModule.class).initReport(buildId);

            StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildId);
            url = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, groupKey);

            StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildId);
            deployUrl = indy.module(IndyFoloContentClientModule.class).trackingUrl(buildId, hostedKey);

            logger.info("Using '{}' for {} repository access in build: {}", url, packageType, buildId);
        } catch (IndyClientException e) {
            logger.debug("Failed to retrieve Indy client module for the artifact tracker");
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy client module for the artifact tracker: %s",
                    e,
                    e.getMessage());
        }
        return new CreateResponse(url, deployUrl, null);
    }

    /**
     * Retrieve tracking report from repository manager. Add each tracked download to the dependencies of the build
     * result. Add each tracked upload to the built artifacts of the build result. Promote uploaded artifacts to the
     * product-level storage. Finally delete the group associated with the completed build.
     */
    public void promote(PromoteRequest promoteRequest) throws RepositoryDriverException {
        if (lifecycle.isShuttingDown()) {
            throw new StoppingException();
        }
        String buildContentId = promoteRequest.getBuildContentId();
        BuildType buildType = promoteRequest.getBuildType();
        TrackedContentDTO report = sealAndGetTrackingReport(buildContentId, true);

        // schedule promotion
        executor.runAsync(() -> {
            lifecycle.addActivePromotion();
            try {
                Runnable heartBeatSender = heartBeatSender(promoteRequest.getHeartBeat());

                List<Artifact> downloadedArtifacts;
                try {
                    logger.info("BEGIN: Process artifacts downloaded by build");
                    userLog.info("Processing dependencies"); // TODO log event duration
                    StopWatch stopWatch = StopWatch.createStarted();

                    downloadedArtifacts = collectDownloadedArtifacts(report.getDownloads());
                    logger.info(
                            "END: Process artifacts downloaded by build, took {} seconds",
                            stopWatch.getTime(TimeUnit.SECONDS));
                } catch (RepositoryDriverException e) {
                    String message = e.getMessage();
                    userLog.error("Dependencies promotion failed. Error(s): {}", message);
                    notifyInvoker(
                            promoteRequest.getCallback(),
                            PromoteResult.failed(buildContentId, message, CompletionStatus.SYSTEM_ERROR));
                    return;
                }

                Uploads uploads = collectUploads(report.getUploads(), promoteRequest.isTempBuild());
                List<Artifact> uploadedArtifacts = uploads.getData();
                Collections.sort(uploadedArtifacts, Comparator.comparing(Artifact::getIdentifier));

                heartBeatSender.run();
                deleteBuildGroup(buildType.getRepoType(), buildContentId);
                promoteDownloads(heartBeatSender, report.getDownloads(), promoteRequest.isTempBuild());
                heartBeatSender.run();
                promoteUploadsToBuildContentSet(
                        buildType.getRepoType(),
                        buildContentId,
                        uploads.getPromotion(),
                        promoteRequest.isTempBuild());
                logger.info(
                        "Returning built artifacts / dependencies:\nUploads:\n  {}\n\nDownloads:\n  {}\n\n",
                        StringUtils.join(uploads.getData(), "\n  "),
                        StringUtils.join(downloadedArtifacts, "\n  "));
                notifyInvoker(
                        promoteRequest.getCallback(),
                        new PromoteResult(
                                uploadedArtifacts,
                                downloadedArtifacts,
                                buildContentId,
                                "",
                                CompletionStatus.SUCCESS));
            } catch (PromotionValidationException e) {
                notifyInvoker(
                        promoteRequest.getCallback(),
                        PromoteResult.failed(buildContentId, e.getMessage(), CompletionStatus.FAILED));
            } catch (RepositoryDriverException e) {
                notifyInvoker(
                        promoteRequest.getCallback(),
                        PromoteResult.failed(buildContentId, e.getMessage(), CompletionStatus.SYSTEM_ERROR));
            }
        }).handle((nul, throwable) -> {
            if (throwable != null) {
                logger.error("Unhanded promotion exception.", throwable);
            }
            lifecycle.removeActivePromotion();
            return null;
        });
    }

    private void notifyInvoker(Request callback, PromoteResult promoteResult) {
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
                .handleIf((response, throwable) -> throwable != null || !isHttpSuccess(response.statusCode()))
                .withMaxDuration(Duration.ofSeconds(configuration.getCallbackRetryDuration()))
                .withBackoff(500, 5000, ChronoUnit.MILLIS)
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
                .getStageAsync(() -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
    }

    public PromoteResult collectRepoManagerResult(String buildContentId, boolean tempBuild)
            throws RepositoryDriverException {
        TrackedContentDTO report = sealAndGetTrackingReport(buildContentId, false);
        try {
            logger.info("BEGIN: Process artifacts downloaded by build");
            userLog.info("Processing dependencies");
            StopWatch stopWatch = StopWatch.createStarted();

            List<Artifact> downloadedArtifacts = collectDownloadedArtifacts(report.getDownloads());
            logger.info(
                    "END: Process artifacts downloaded by build, took {} seconds",
                    stopWatch.getTime(TimeUnit.SECONDS)); // TODO log-event-duration

            Uploads uploads = collectUploads(report.getUploads(), tempBuild);
            List<Artifact> uploadedArtifacts = uploads.getData();
            Collections.sort(uploadedArtifacts, Comparator.comparing(Artifact::getIdentifier));

            logger.info(
                    "Returning built artifacts / dependencies:\nUploads:\n  {}\n\nDownloads:\n  {}\n\n",
                    StringUtils.join(uploads.getData(), "\n  "),
                    StringUtils.join(downloadedArtifacts, "\n  "));
            return new PromoteResult(
                    uploadedArtifacts,
                    downloadedArtifacts,
                    buildContentId,
                    "",
                    CompletionStatus.SUCCESS);
        } catch (RepositoryDriverException e) {
            String message = e.getMessage();
            logger.warn("Dependencies promotion failed. Error(s): {}", message);
            userLog.error("Built artifact promotion failed. Error(s): {}", message);
            return new PromoteResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    buildContentId,
                    message,
                    CompletionStatus.FAILED);
        }
    }

    /**
     * Return list of output artifacts for promotion.
     *
     * @return List of output artifacts meta data
     * @throws RepositoryDriverException In case of a client API transport error or an error during promotion of
     *         artifacts
     */
    private Uploads collectUploads(Set<TrackedContentEntryDTO> uploads, boolean tempBuild)
            throws RepositoryDriverException {
        List<Artifact> data = new ArrayList<>();

        userLog.info("Processing built artifacts"); // TODO duration
        StopWatch stopWatch = StopWatch.createStarted();

        Set<String> promotionSet = new HashSet<>();

        IndyContentClientModule content;
        try {
            content = indy.content();
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy content module. Reason: %s",
                    e,
                    e.getMessage());
        }

        for (TrackedContentEntryDTO upload : uploads) {
            String path = upload.getPath();
            StoreKey storeKey = upload.getStoreKey();

            if (artifactFilter.acceptsForData(upload)) {
                String identifier = computeIdentifier(upload);

                logger.info("Recording upload: {}", identifier);

                RepositoryType repoType = TypeConverters.toRepoType(storeKey.getPackageType());
                TargetRepository targetRepository = getUploadsTargetRepository(repoType, tempBuild, content);

                ArtifactQuality artifactQuality = getArtifactQuality(tempBuild);
                Artifact artifact = Artifact.builder()
                        .md5(upload.getMd5())
                        .sha1(upload.getSha1())
                        .sha256(upload.getSha256())
                        .size(upload.getSize())
                        .artifactQuality(artifactQuality)
                        .deployPath(upload.getPath())
                        .filename(new File(path).getName())
                        .identifier(identifier)
                        .targetRepository(targetRepository)
                        .build();

                data.add(validateArtifact(artifact));
            }

            if (artifactFilter.acceptsForPromotion(upload, false)) {
                promotionSet.add(path);
                if (MAVEN_PKG_KEY.equals(storeKey.getPackageType()) && !isChecksum(path)) {
                    // add the standard checksums to ensure, they are promoted (Maven usually uses only one, so
                    // the other would be missing) but avoid adding checksums of checksums.
                    promotionSet.add(path + ".md5");
                    promotionSet.add(path + ".sha1");
                }
            }
        }
        logger.info("END: Process artifacts uploaded from build, took {} seconds", stopWatch.getTime(TimeUnit.SECONDS));
        return new Uploads(data, new ArrayList<>(promotionSet));
    }

    private TrackedContentDTO sealAndGetTrackingReport(String buildContentId, boolean seal)
            throws RepositoryDriverException {
        TrackedContentDTO report;
        try {
            IndyFoloAdminClientModule foloAdmin = indy.module(IndyFoloAdminClientModule.class);
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

    /**
     * Create the hosted repository and group necessary to support a single build. The hosted repository holds artifacts
     * uploaded from the build, and the group coordinates access to this hosted repository, along with content from the
     * product-level content group with which this build is associated. The group also provides a tracking target, so
     * the repository manager can keep track of downloads and uploads for the build.
     *
     * @param packageType the package type key used by Indy
     * @throws IndyClientException
     */
    private void setupBuildRepos(
            String buildId, // TODO used only for logs, should we use buildContentId instead
            String buildContentId,
            BuildType buildType,
            String packageType,
            boolean tempBuild,
            // add extra repositories removed from poms by the adjust process and set in BC by user
            // TODO validate repository see #extractExtraRepositoriesFromGenericParameters
            List<ArtifactRepository> extraDependencyRepositories) throws IndyClientException {

        // if the build-level group doesn't exist, create it.
        StoreKey groupKey = new StoreKey(packageType, StoreType.group, buildContentId);
        StoreKey hostedKey = new StoreKey(packageType, StoreType.hosted, buildContentId);

        if (!indy.stores().exists(groupKey)) {
            // if the product-level storage repo (for in-progress product builds) doesn't exist, create it.

            if (!indy.stores().exists(hostedKey)) {
                HostedRepository buildArtifacts = new HostedRepository(packageType, buildContentId);
                buildArtifacts.setAllowSnapshots(false);
                buildArtifacts.setAllowReleases(true);

                buildArtifacts.setDescription(String.format("Build output for PNC %s build #%s", packageType, buildId));

                indy.stores()
                        .create(
                                buildArtifacts,
                                "Creating hosted repository for " + packageType + " build: " + buildId + " (repo: "
                                        + buildContentId + ")",
                                HostedRepository.class);
            }

            Group buildGroup = new Group(packageType, buildContentId);
            String adjective = tempBuild ? "temporary " : "";
            buildGroup.setDescription(String.format("Aggregation group for PNC %sbuild #%s", adjective, buildId));

            // build-local artifacts
            buildGroup.addConstituent(hostedKey);

            // Global-level repos, for captured/shared artifacts and access to the outside world
            addGlobalConstituents(buildType, packageType, buildGroup, tempBuild);

            addExtraConstituents(packageType, extraDependencyRepositories, buildId, buildContentId, buildGroup);

            String changelog = "Creating repository group for resolving artifacts in build: " + buildId + " (repo: "
                    + buildContentId + ")";
            indy.stores().create(buildGroup, changelog, Group.class);
        }
    }

    /**
     * Adds extra remote repositories to the build group that are requested for the particular build. For a Maven build
     * these are repositories defined in the root pom removed by PME by the adjust process.
     *
     * @param packageType the package type key used by Indy
     * @param repositories the list of repositories to be added
     * @param buildId build ID
     * @param buildContentId build content ID
     * @param buildGroup target build group in which the repositories are added
     *
     * @throws IndyClientException in case of an issue when communicating with the repository manager
     */
    private void addExtraConstituents(
            String packageType,
            List<ArtifactRepository> repositories,
            String buildId,
            String buildContentId,
            Group buildGroup) throws IndyClientException {
        if (repositories != null && !repositories.isEmpty()) {
            StoreListingDTO<RemoteRepository> existingRepos = indy.stores().listRemoteRepositories(packageType);
            for (ArtifactRepository repository : repositories) {
                StoreKey remoteKey = null;
                for (RemoteRepository existingRepo : existingRepos) {
                    if (StringUtils.equals(existingRepo.getUrl(), repository.getUrl())) {
                        remoteKey = existingRepo.getKey();
                        break;
                    }
                }

                if (remoteKey == null) {
                    // this is basically an implied repo, so using the same prefix "i-"
                    String remoteName = "i-" + convertIllegalCharacters(repository.getId());

                    // find a free repository ID for the newly created repo
                    remoteKey = new StoreKey(packageType, StoreType.remote, remoteName);
                    int i = 2;
                    while (indy.stores().exists(remoteKey)) {
                        remoteKey = new StoreKey(packageType, StoreType.remote, remoteName + "-" + i++);
                    }

                    RemoteRepository remoteRepo = new RemoteRepository(
                            packageType,
                            remoteKey.getName(),
                            repository.getUrl());
                    remoteRepo.setAllowReleases(repository.getReleases());
                    remoteRepo.setAllowSnapshots(repository.getSnapshots());
                    remoteRepo.setDescription(
                            "Implicitly created " + packageType + " repo for: " + repository.getName() + " ("
                                    + repository.getId() + ") from repository declaration removed by PME in build "
                                    + buildId + " (repo: " + buildContentId + ")");
                    indy.stores()
                            .create(
                                    remoteRepo,
                                    "Creating extra remote repository " + repository.getName() + " ("
                                            + repository.getId() + ") for build: " + buildId + " (repo: "
                                            + buildContentId + ")",
                                    RemoteRepository.class);
                }

                buildGroup.addConstituent(remoteKey);
            }
        }
    }

    /**
     * Converts characters in a given string considered as illegal by Indy to underscores.
     *
     * @param name repository name
     * @return string with converted characters
     */
    private String convertIllegalCharacters(String name) {
        char[] result = new char[name.length()];
        for (int i = 0; i < name.length(); i++) {
            char checkedChar = name.charAt(i);
            if (Character.isLetterOrDigit(checkedChar) || checkedChar == '+' || checkedChar == '-'
                    || checkedChar == '.') {
                result[i] = checkedChar;
            } else {
                result[i] = '_';
            }
        }
        return String.valueOf(result);
    }

    /**
     * Add the constituents that every build repository group should contain:
     * <ol>
     * <li>builds-untested (Group)</li>
     * <li>for temporary builds add also temporary-builds (Group)</li>
     * <li>shared-imports (Hosted Repo)</li>
     * <li>public (Group)</li>
     * <li>any build-type-specific repos</li>
     * </ol>
     *
     * @param buildType the build type
     * @param pakageType package type key used by Indy (is defined by the build type, passed in just to avoid computing
     *        it again)
     */
    private void addGlobalConstituents(BuildType buildType, String pakageType, Group group, boolean tempBuild) {
        // 1. global builds artifacts
        if (tempBuild) {
            group.addConstituent(new StoreKey(pakageType, StoreType.hosted, TEMPORARY_BUILDS_GROUP));
        }
        group.addConstituent(new StoreKey(pakageType, StoreType.group, COMMON_BUILD_GROUP_CONSTITUENTS_GROUP));

        // add build-type-specific constituents
        switch (buildType) {
            case GRADLE:
                group.addConstituent(StoreKey.fromString(GRADLE_PLUGINS_REPO));
                break;

            default:
                // no build-type-specific constituents for others
                break;
        }
    }

    private List<Artifact> collectDownloadedArtifacts(Set<TrackedContentEntryDTO> downloads)
            throws RepositoryDriverException {
        IndyContentClientModule content;
        try {
            content = indy.content();
        } catch (IndyClientException e) {
            throw new RepositoryDriverException(
                    "Failed to retrieve Indy content module. Reason: %s",
                    e,
                    e.getMessage());
        }

        List<Artifact> deps = new ArrayList<>(downloads.size());
        for (TrackedContentEntryDTO download : downloads) {
            String path = download.getPath();
            if (artifactFilter.acceptsForData(download)) {
                String identifier = computeIdentifier(download);

                logger.info("Recording download: {}", identifier);

                String originUrl = download.getOriginUrl();
                if (originUrl == null) {
                    // this is from a hosted repository, either shared-imports or a build, or something like that.
                    originUrl = download.getLocalUrl();
                }

                TargetRepository targetRepository = getDownloadsTargetRepository(download, content);

                Artifact.Builder artifactBuilder = Artifact.builder()
                        .md5(download.getMd5())
                        .sha1(download.getSha1())
                        .sha256(download.getSha256())
                        .size(download.getSize())
                        .deployPath(path)
                        .originUrl(originUrl)
                        .importDate(Instant.now())
                        .filename(new File(path).getName())
                        .identifier(identifier)
                        .targetRepository(targetRepository);

                Artifact artifact = validateArtifact(artifactBuilder.build());
                deps.add(artifact);
            }
        }
        Collections.sort(deps, Comparator.comparing(Artifact::getIdentifier));
        return deps;
    }

    private Map<StoreKey, Map<StoreKey, Set<String>>> collectDownloadsPromotionMap(
            Set<TrackedContentEntryDTO> downloads) {
        Map<StoreKey, Map<StoreKey, Set<String>>> depMap = new HashMap<>();
        Map<String, StoreKey> promotionTargets = new HashMap<>();
        for (TrackedContentEntryDTO download : downloads) {
            String path = download.getPath();
            StoreKey source = download.getStoreKey();
            String packageType = source.getPackageType();
            if (artifactFilter.acceptsForPromotion(download, true)) {
                StoreKey target = null;
                Map<StoreKey, Set<String>> sources = null;
                Set<String> paths = null;

                // this has not been captured, so promote it.
                switch (packageType) {
                    case MAVEN_PKG_KEY:
                    case NPM_PKG_KEY:
                        target = getPromotionTarget(packageType, promotionTargets);
                        sources = depMap.computeIfAbsent(target, t -> new HashMap<>());
                        paths = sources.computeIfAbsent(source, s -> new HashSet<>());

                        paths.add(path);
                        if (MAVEN_PKG_KEY.equals(packageType) && !isChecksum(path)) {
                            // add the standard checksums to ensure, they are promoted (Maven usually uses only one, so
                            // the other would be missing) but avoid adding checksums of checksums.
                            paths.add(path + ".md5");
                            paths.add(path + ".sha1");
                        }
                        break;

                    case GENERIC_PKG_KEY:
                        String remoteName = source.getName();
                        String hostedName = getGenericHostedRepoName(remoteName);
                        target = new StoreKey(packageType, StoreType.hosted, hostedName);
                        sources = depMap.computeIfAbsent(target, t -> new HashMap<>());
                        paths = sources.computeIfAbsent(source, s -> new HashSet<>());

                        paths.add(path);
                        break;

                    default:
                        // do not promote anything else anywhere
                        break;
                }
            }
        }

        return depMap;
    }

    private boolean isChecksum(String path) {
        String suffix = StringUtils.substringAfterLast(path, ".");
        return Checksum.suffixes.contains(suffix);
    }

    /**
     * Promotes by path downloads captured in given map. The key in the map is promotion target store key. The value is
     * another map, where key is promotion source store key and value is list of paths to be promoted.
     *
     * @throws RepositoryDriverException in case of an unexpected error during promotion
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     */
    private void promoteDownloads(Runnable heartBeatSender, Set<TrackedContentEntryDTO> downloads, boolean tempBuild)
            throws RepositoryDriverException, PromotionValidationException {
        // Promote all build dependencies NOT ALREADY CAPTURED to the hosted repository holding store for the shared
        // imports
        // and return dependency artifacts meta data.
        Map<StoreKey, Map<StoreKey, Set<String>>> depMap = collectDownloadsPromotionMap(downloads);

        for (Map.Entry<StoreKey, Map<StoreKey, Set<String>>> targetToSources : depMap.entrySet()) {
            StoreKey target = targetToSources.getKey();
            for (Map.Entry<StoreKey, Set<String>> sourceToPaths : targetToSources.getValue().entrySet()) {
                heartBeatSender.run();
                StoreKey source = sourceToPaths.getKey();
                CallbackTarget callbackTarget = new CallbackTarget(
                        configuration.getSelfBaseUrl(),
                        CallbackTarget.CallbackMethod.PUT,
                        MdcUtils.mdcToMapWithHeaderKeys());
                PathsPromoteRequest req = new PathsPromoteRequest(source, target, sourceToPaths.getValue())
                        .setPurgeSource(false);
                // set read-only only the generic http proxy hosted repos, not shared-imports
                boolean readonly = !tempBuild && GENERIC_PKG_KEY.equals(target.getPackageType());

                StopWatch stopWatchDoPromote = StopWatch.createStarted();
                try {
                    logger.info(
                            "BEGIN: doPromoteByPath: source: '{}', target: '{}', readonly: {}",
                            req.getSource().toString(),
                            req.getTarget().toString(),
                            readonly);
                    userLog.info(
                            "Promoting {} dependencies from {} to {}",
                            req.getPaths().size(),
                            req.getSource(),
                            req.getTarget());

                    doPromoteByPath(req, false, readonly);

                    logger.info(
                            "END: doPromoteByPath: source: '{}', target: '{}', readonly: {}, took: {} seconds",
                            req.getSource().toString(),
                            req.getTarget().toString(),
                            readonly,
                            stopWatchDoPromote.getTime(TimeUnit.SECONDS));
                } catch (RepositoryDriverException ex) {
                    logger.info(
                            "END: doPromoteByPath: source: '{}', target: '{}', readonly: {}, took: {} seconds",
                            req.getSource().toString(),
                            req.getTarget().toString(),
                            readonly,
                            stopWatchDoPromote.getTime(TimeUnit.SECONDS));
                    userLog.error("Dependencies promotion failed. Error(s): {}", ex.getMessage()); // TODO unify log
                    throw ex;
                }
            }
        }
    }

    private StoreKey getPromotionTarget(String packageType, Map<String, StoreKey> promotionTargets) {
        if (!promotionTargets.containsKey(packageType)) {
            StoreKey storeKey = new StoreKey(packageType, StoreType.hosted, SHARED_IMPORTS_ID);
            promotionTargets.put(packageType, storeKey);
        }
        return promotionTargets.get(packageType);
    }

    private TargetRepository getDownloadsTargetRepository(
            TrackedContentEntryDTO download,
            IndyContentClientModule content) throws RepositoryDriverException {
        String identifier;
        String repoPath;
        StoreKey source = download.getStoreKey();
        RepositoryType repoType = TypeConverters.toRepoType(source.getPackageType());
        if (repoType == RepositoryType.MAVEN || repoType == RepositoryType.NPM) {
            identifier = "indy-" + repoType.name().toLowerCase();
            repoPath = getTargetRepositoryPath(download, content);
        } else if (repoType == RepositoryType.GENERIC_PROXY) {
            identifier = "indy-http";
            repoPath = getGenericTargetRepositoryPath(source);
        } else {
            throw new RepositoryDriverException(
                    "Repository type " + repoType + " is not supported by Indy repo manager driver.");
        }
        if (!repoPath.endsWith("/")) {
            repoPath += '/';
        }

        return TargetRepository.refBuilder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(false)
                .build();
    }

    private String getTargetRepositoryPath(TrackedContentEntryDTO download, IndyContentClientModule content) {
        String result;
        StoreKey sk = download.getStoreKey();
        String packageType = sk.getPackageType();
        if (artifactFilter.ignoreDependencySource(sk)) {
            result = "/api/" + content.contentPath(sk);
        } else {
            result = "/api/" + content.contentPath(new StoreKey(packageType, StoreType.hosted, SHARED_IMPORTS_ID));
        }
        return result;
    }

    /**
     * For a remote generic http repo computes matching hosted repo name.
     *
     * @param remoteName the remote repo name
     * @return computed hosted repo name
     */
    private String getGenericHostedRepoName(String remoteName) {
        String hostedName;
        if (remoteName.startsWith("r-")) {
            hostedName = "h-" + remoteName.substring(2);
        } else {
            logger.warn(
                    "Unexpected generic http remote repo name {}. Using it for hosted repo "
                            + "without change, but it probably doesn't exist.",
                    remoteName);
            hostedName = remoteName;
        }
        return hostedName;
    }

    private String getGenericTargetRepositoryPath(StoreKey source) {
        return "/api/content/generic-http/hosted/" + getGenericHostedRepoName(source.getName());
    }

    private TargetRepository getUploadsTargetRepository(
            RepositoryType repoType,
            boolean tempBuild,
            IndyContentClientModule content) throws RepositoryDriverException {

        StoreKey storeKey;
        String identifier;
        if (repoType == RepositoryType.MAVEN) {
            storeKey = new StoreKey(MAVEN_PKG_KEY, StoreType.hosted, getBuildPromotionTarget(tempBuild));
            identifier = ReposiotryIdentifier.INDY_MAVEN;
        } else if (repoType == RepositoryType.NPM) {
            storeKey = new StoreKey(NPM_PKG_KEY, StoreType.hosted, getBuildPromotionTarget(tempBuild));
            identifier = ReposiotryIdentifier.INDY_NPM;
        } else {
            throw new RepositoryDriverException(
                    "Repository type " + repoType + " is not supported for uploads by Indy repo manager driver.");
        }

        String repoPath = "/api/" + content.contentPath(storeKey);
        if (!repoPath.endsWith("/")) {
            repoPath += '/';
        }
        return TargetRepository.refBuilder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(tempBuild)
                .build();
    }

    /**
     * Computes identifier string for an artifact. If the download path is valid for a package-type specific artifact it
     * creates the identifier accordingly.
     *
     * @param transfer the download or upload that we want to generate identifier for
     * @return generated identifier
     */
    private String computeIdentifier(final TrackedContentEntryDTO transfer) {
        String identifier = null;

        switch (transfer.getStoreKey().getPackageType()) {
            case MAVEN_PKG_KEY:
                ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(transfer.getPath());
                if (pathInfo != null) {
                    ArtifactRef aref = new SimpleArtifactRef(
                            pathInfo.getProjectId(),
                            pathInfo.getType(),
                            pathInfo.getClassifier());
                    identifier = aref.toString();
                }
                break;

            case NPM_PKG_KEY:
                NpmPackagePathInfo npmPathInfo = NpmPackagePathInfo.parse(transfer.getPath());
                if (npmPathInfo != null) {
                    NpmPackageRef packageRef = new NpmPackageRef(npmPathInfo.getName(), npmPathInfo.getVersion());
                    identifier = packageRef.toString();
                }
                break;

            case GENERIC_PKG_KEY:
                // handle generic downloads along with other invalid download paths for other package types
                break;

            default:
                // do not do anything by default
                logger.warn(
                        "Package type {} is not handled by Indy repository session.",
                        transfer.getStoreKey().getPackageType());
                break;
        }

        if (identifier == null) {
            identifier = computeGenericIdentifier(
                    transfer.getOriginUrl(),
                    transfer.getLocalUrl(),
                    transfer.getSha256());
        }

        return identifier;
    }

    /**
     * Compute the identifier string for a generic download, that does not match package type specific files structure.
     * It prefers to use the origin URL if it is not empty. In case it is then it uses local URL, which can never be
     * empty, it is the local file mirror in Indy. After that it attaches the sha256 separated by a pipe.
     *
     * @param originUrl the origin URL of the transfer, it can be null
     * @param localUrl url where the artifact was backed up in Indy
     * @param sha256 the SHA-256 of the transfer
     * @return the generated identifier
     */
    private String computeGenericIdentifier(String originUrl, String localUrl, String sha256) {
        String identifier = originUrl;
        if (identifier == null) {
            // this is from/to a hosted repository, either the build repo or something like that.
            identifier = localUrl;
        }
        identifier += '|' + sha256;
        return identifier;
    }

    /**
     * Check artifact for any validation errors. If there are constraint violations, then a RepositoryManagerException
     * is thrown. Otherwise the artifact is returned.
     *
     * @param artifact to validate
     * @return the same artifact
     * @throws RepositoryDriverException if there are constraint violations
     */
    private Artifact validateArtifact(Artifact artifact) throws RepositoryDriverException {
        Set<ConstraintViolation<Artifact>> violations = validator.validate(artifact);
        if (!violations.isEmpty()) {
            throw new RepositoryDriverException(
                    "Repository manager returned invalid artifact: " + artifact.toString()
                            + " Constraint Violations: %s",
                    violations);
        }
        return artifact;
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
     * Promote the build output to the consolidated build repo (using path promotion, where the build repo contents are
     * added to the repo's contents) and marks the build output as readonly.
     *
     * @param uploads artifacts to be promoted
     * @throws RepositoryDriverException when the repository client API throws an exception due to something unexpected
     *         in transport
     * @throws PromotionValidationException when the promotion process results in an error due to validation failure
     */
    public void promoteUploadsToBuildContentSet(
            RepositoryType repositoryType,
            String buildContentId,
            List<String> uploads,
            boolean tempBuild) throws RepositoryDriverException, PromotionValidationException {
        userLog.info("Validating and promoting built artifacts");

        String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);

        try {
            StoreKey source = new StoreKey(packageType, StoreType.hosted, buildContentId);
            StoreKey target = new StoreKey(packageType, StoreType.hosted, getBuildPromotionTarget(tempBuild));

            PathsPromoteRequest request = new PathsPromoteRequest(source, target, new HashSet<>(uploads));

            doPromoteByPath(request, !tempBuild, false);
        } catch (RepositoryDriverException | PromotionValidationException ex) {
            userLog.error("Built artifact promotion failed. Error(s): {}", ex.getMessage());
            throw ex;
        }

        logger.info("END: promotion to build content set."); // TODO use log duration
    }

    private String getBuildPromotionTarget(boolean tempBuild) {
        return tempBuild ? configuration.getTempBuildPromotionTarget() : configuration.getBuildPromotionTarget();
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

    private ArtifactQuality getArtifactQuality(boolean isTempBuild) {
        if (isTempBuild) {
            return ArtifactQuality.TEMPORARY;
        } else {
            return ArtifactQuality.NEW;
        }
    }

    public void deleteBuildGroup(RepositoryType repositoryType, String buildContentId)
            throws RepositoryDriverException {
        logger.info("BEGIN: Removing build aggregation group: {}", buildContentId);
        userLog.info("Removing build aggregation group");
        StopWatch stopWatch = StopWatch.createStarted(); // TODO log event duration

        try {
            String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);
            StoreKey key = new StoreKey(packageType, StoreType.group, buildContentId);
            indy.stores().delete(key, "[Post-Build] Removing build aggregation group: " + buildContentId);
        } catch (IndyClientException e) {
            throw new RepositoryDriverException("Failed to retrieve Indy stores module. Reason: %s", e, e.getMessage());
        }
        logger.info(
                "END: Removing build aggregation group: {}, took: {} seconds",
                buildContentId,
                stopWatch.getTime(TimeUnit.SECONDS));
        stopWatch.reset();
    }

    private boolean isHttpSuccess(int responseCode) {
        return responseCode >= 200 && responseCode < 300;
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

    // TODO move out of the driver
    List<ArtifactRepository> extractExtraRepositoriesFromGenericParameters(Map<String, String> genericParameters) {
        String extraReposString = genericParameters.get(BuildConfigurationParameterKeys.EXTRA_REPOSITORIES.name());
        if (extraReposString == null) {
            return new ArrayList<>();
        }

        return Arrays.stream(extraReposString.split("\n")).map((repoString) -> {
            try {
                String id = new URL(repoString).getHost().replaceAll("\\.", "-");
                return new ArtifactRepository(id, id, repoString.trim(), true, false);
            } catch (MalformedURLException e) {
                userLog.warn("Malformed repository URL entered: " + repoString + ". Skipping.");
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private class Uploads {

        /** List of artifacts to be stored in DB. */
        private List<Artifact> data;

        /** List of paths to be promoted. */
        private List<String> promotion;

        private Uploads(List<Artifact> data, List<String> promotion) {
            this.data = data;
            this.promotion = promotion;
        }

        /**
         * Gets the list of uploaded artifacts to be stored in DB.
         *
         * @return the list
         */
        public List<Artifact> getData() {
            return data;
        }

        /**
         * Gets the list of paths for promotion.
         *
         * @return the list
         */
        public List<String> getPromotion() {
            return promotion;
        }
    }
}
