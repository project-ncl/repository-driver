package org.jboss.pnc.repositorydriver;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.jboss.pnc.repositorydriver.ArchiveDownloadEntry.fromTrackedEntry;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.MVN_SHARED_IMPORTS_ID;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.NPM_SHARED_IMPORTS_ID;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.apache.commons.lang3.StringUtils;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.commonjava.atlas.npm.ident.util.NpmPackagePathInfo;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.enums.ArtifactQuality;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.common.Strings;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilter;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterArchive;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterPromotion;
import org.jboss.pnc.repositorydriver.artifactfilter.PatternsList;
import org.jboss.pnc.repositorydriver.buildinfo.BuildInfoPromotion;
import org.jboss.pnc.repositorydriver.constants.RepositoryConstants;
import org.jboss.pnc.repositorydriver.rest.PNCClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.Builder;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class TrackingReportProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TrackingReportProcessor.class);

    /** NCL-7238: Add this extension to parse for maven urls with no extensions */
    public final static String MAVEN_SUBSTITUTE_EXTENSION = ".empty";

    @Inject
    ArtifactFilterArchive artifactFilterArchive;

    @Inject
    ArtifactFilterDatabase artifactFilterDatabase;

    @Inject
    ArtifactFilterPromotion artifactFilterPromotion;

    @Inject
    Validator validator;

    @Inject
    Configuration configuration;

    @Inject
    @RestClient
    PNCClient pncClient;

    private PatternsList ignoredRepoPatterns;

    @PostConstruct
    public void init() {
        if (configuration.getIgnoredRepoPatternsPromotion().isPresent()) {
            ignoredRepoPatterns = new PatternsList(configuration.getIgnoredRepoPatternsPromotion().get());
        } else {
            ignoredRepoPatterns = new PatternsList(Collections.emptyList());
        }
    }

    @WithSpan()
    public List<RepositoryArtifact> collectDownloadedArtifacts(
            @SpanAttribute(value = "report") TrackingReport report,
            @SpanAttribute(value = "filter") ArtifactFilter filter) throws RepositoryDriverException {
        Set<TrackedEntry> downloads = report.getDownloads();
        if (downloads == null) {
            return Collections.emptyList();
        }

        List<RepositoryArtifact> deps = new ArrayList<>(downloads.size());
        for (TrackedEntry download : downloads) {
            if (filter.accepts(download)) {
                String path = download.getPath();
                RepositoryId repoId = download.getRepoId();
                String identifier = computeIdentifier(download);
                String originUrl = download.getOriginUrl();
                if (originUrl == null) {
                    // this is from a hosted repository, either shared-imports or a build, or something like that.
                    originUrl = download.getLocalUrl();
                }

                TargetRepository targetRepository = getDownloadsTargetRepository(download);

                // ignored dependency sources for promotion are the internal ones, so those artifacts are built inhouse
                ArtifactQuality quality = ignoreDependencySource(repoId) ? ArtifactQuality.NEW
                        : ArtifactQuality.IMPORTED;

                String filename = getDownloadFilename(path, originUrl, targetRepository.getRepositoryType());
                String purl = computePurl(download, filename);

                RepositoryArtifact.Builder artifactBuilder = RepositoryArtifact.builder()
                        .md5(download.getMd5())
                        .sha1(download.getSha1())
                        .sha256(download.getSha256())
                        .size(download.getSize())
                        .deployPath(path)
                        .originUrl(originUrl)
                        .importDate(Instant.now())
                        .filename(filename)
                        .identifier(identifier)
                        .purl(purl)
                        .artifactQuality(quality)
                        .targetRepository(targetRepository);

                RepositoryArtifact artifact = validateArtifact(artifactBuilder.build());
                deps.add(artifact);
            }
        }
        deps.sort(Comparator.comparing(RepositoryArtifact::getIdentifier));
        return deps;
    }

    /**
     * Gets the download filename. For regular dependencies it uses the deploypath to get it. But for generic-http
     * downloads the deploypath is hashed and can be longer than 255 characters, so it extracts the filename from the
     * originUrl.
     *
     * @param deployPath the download's deploy path
     * @param originUrl the origin URL, can be null for local dependencies
     * @param repoType the repository type used to recognize generic-http downloads
     * @return the extracted filename
     */
    private String getDownloadFilename(String deployPath, String originUrl, RepositoryType repoType) {
        String filename = null;
        if (RepositoryType.GENERIC_PROXY.equals(repoType)) {
            // for generic-http downloads the deploypath is hashed, so we use the originurl to get the filename
            try {
                URL url = new URL(originUrl);
                filename = new File(url.getFile()).getName();
            } catch (MalformedURLException ex) {
                logger.error("Unable to parse the origin URL " + originUrl, ex);
            }
        }
        if (filename == null) {
            filename = new File(deployPath).getName();
        }
        return filename;
    }

    /**
     * Checks if given repository is ignored for dependencies promotion.
     *
     * @param repoId evaluated repository ID
     * @return true if the given repository is ignored, false otherwise
     */
    private boolean ignoreDependencySource(RepositoryId repoId) {
        String repoPath = repoId.getPath();
        return ignoredRepoPatterns.matchesOne(repoPath);
    }

    /**
     * Return list of output artifacts for promotion.
     *
     * @return List of output artifacts meta data
     * @throws RepositoryDriverException In case of a client API transport error or an error during promotion of
     *         artifacts
     */
    @WithSpan()
    public List<RepositoryArtifact> collectUploadedArtifacts(
            @SpanAttribute(value = "report") TrackingReport report,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "buildCategory") BuildCategory buildCategory) throws RepositoryDriverException {

        Set<TrackedEntry> uploads = report.getUploads();
        if (uploads == null) {
            return Collections.emptyList();
        }
        List<RepositoryArtifact> artifacts = new ArrayList<>(uploads.size());
        for (TrackedEntry upload : uploads) {
            String path = upload.getPath();
            PackageType packageType = upload.getRepoId().getPackageType();

            if (artifactFilterDatabase.accepts(upload)) {
                String identifier = computeIdentifier(upload);
                String filename = new File(path).getName();
                String purl = computePurl(upload, filename);

                logger.info("Recording upload: {}", identifier);
                RepositoryType repoType = TypeConverters.toRepoType(packageType);
                TargetRepository targetRepository = getUploadsTargetRepository(repoType, buildCategory, tempBuild);

                RepositoryArtifact artifact = RepositoryArtifact.builder()
                        .md5(upload.getMd5())
                        .sha1(upload.getSha1())
                        .sha256(upload.getSha256())
                        .size(upload.getSize())
                        .deployPath(upload.getPath())
                        .filename(filename)
                        .identifier(identifier)
                        .purl(purl)
                        .artifactQuality(tempBuild ? ArtifactQuality.TEMPORARY : ArtifactQuality.NEW)
                        .targetRepository(targetRepository)
                        .buildCategory(buildCategory)
                        .build();

                artifacts.add(validateArtifact(artifact));
            }
        }
        return artifacts;
    }

    @WithSpan()
    public List<ArchiveDownloadEntry> collectArchivalArtifacts(
            @SpanAttribute(value = "report") TrackingReport report) throws RepositoryDriverException {
        Set<TrackedEntry> downloads = report.getDownloads();
        if (downloads == null) {
            return Collections.emptyList();
        }

        List<ArchiveDownloadEntry> deps = new ArrayList<>(downloads.size());
        for (TrackedEntry download : downloads) {
            if (artifactFilterArchive.accepts(download)) {
                TargetRepository targetRepository = getDownloadsTargetRepository(download);
                ArchiveDownloadEntry entry = fromTrackedEntry(download, targetRepository);
                deps.add(entry);
            }
        }
        deps.sort(Comparator.comparing(ArchiveDownloadEntry::getRepositoryId));
        return deps;
    }

    /**
     * Creates TWO BuildInfo objects for promotion: primary Build and generic downloads Build.
     *
     * <p>
     * This method creates TWO separate Build objects because Artifactory cannot differentiate between modules during
     * promotion:
     * </p>
     * <ul>
     * <li>Primary Build: Contains artifacts (uploads) and dependencies (Maven/NPM downloads)</li>
     * <li>Generic Build: Contains generic downloads as dependencies (may be null if no generic downloads)</li>
     * </ul>
     *
     * <p>
     * Each Build is uploaded and promoted separately:
     * </p>
     * <ul>
     * <li>Primary Build → artifacts target (e.g., pnc-mvn-builds) and dependencies target (e.g.,
     * pnc-mvn-imports)</li>
     * <li>Generic Build → generic downloads target (e.g., pnc-generic-downloads)</li>
     * </ul>
     *
     * <p>
     * Filtering logic:
     * </p>
     * <ul>
     * <li>Downloads: filtered by both ignoreDependencySource() and artifactFilterPromotion.accepts()</li>
     * <li>Uploads: filtered by artifactFilterPromotion.accepts()</li>
     * </ul>
     *
     * @param report the tracking report containing uploads and downloads
     * @param tempBuild whether this is a temporary build
     * @param buildContentId the build content ID (tracking ID)
     * @param repositoryType the repository type for uploads
     * @param buildCategory the build category
     * @return BuildInfoPromotion containing both Builds and their target repositories
     * @throws RepositoryDriverException if BuildInfo creation fails
     */
    @WithSpan()
    public BuildInfoPromotion createPromotionBuildInfo(
            @SpanAttribute(value = "report") TrackingReport report,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "buildContentId") String buildContentId,
            @SpanAttribute(value = "repositoryType") RepositoryType repositoryType,
            @SpanAttribute(value = "buildCategory") BuildCategory buildCategory)
            throws RepositoryDriverException {

        Set<TrackedEntry> filteredUploads = new HashSet<>();
        Set<TrackedEntry> filteredDownloads = new HashSet<>();
        Set<TrackedEntry> filteredGenericDownloads = new HashSet<>();
        RepositoryId artifactsTarget = null;
        RepositoryId dependenciesTarget = null;
        RepositoryId genericDownloadsTarget = null;
        Map<PackageType, RepositoryId> promotionTargetsCache = new HashMap<>();

        // Use repository type as agent name (e.g., "MAVEN", "NPM")
        String buildAgentName = repositoryType.name();
        String buildAgentVersion = null;
        String startTime = null;

        // Fetch build information from PNC to get build agent details and start time
        if (isNotEmpty(configuration.getPncUrl())) {
            String build = buildContentId.replace("build-", "");
            logger.debug("Fetching build information from PNC for build {}", build);
            Build pncBuild = pncClient.getSpecific(build);

            if (pncBuild != null) {
                // Extract start time
                if (pncBuild.getStartTime() != null) {
                    startTime = pncBuild.getStartTime().toString();
                }

                // Extract build agent name and version from environment attributes
                if (pncBuild.getEnvironment() != null && pncBuild.getEnvironment().getAttributes() != null) {
                    buildAgentVersion = pncBuild.getEnvironment().getAttributes().get(buildAgentName);
                }

                logger.debug(
                        "Build info for {}: startTime={}, buildAgent={}:{}",
                        build,
                        startTime,
                        buildAgentName,
                        buildAgentVersion);
            }
        }

        // Process uploads with filtering and determine artifacts target
        Set<TrackedEntry> uploads = report.getUploads();
        PackageType uploadsPackageType = null;

        if (uploads != null && !uploads.isEmpty()) {
            uploadsPackageType = TypeConverters.toPackageType(repositoryType);

            // Determine target for uploads
            artifactsTarget = RepositoryId.builder()
                    .project(configuration.getDeploymentType().toString())
                    .packageType(uploadsPackageType)
                    .name(getBuildPromotionTarget(buildCategory, tempBuild))
                    .build();

            for (TrackedEntry upload : uploads) {
                // Apply filter for uploads
                if (artifactFilterPromotion.accepts(upload)) {
                    filteredUploads.add(upload);
                }
            }
        }

        // Process downloads with filtering and determine dependencies target
        Set<TrackedEntry> downloads = report.getDownloads();
        if (downloads != null) {
            for (TrackedEntry download : downloads) {
                RepositoryId sourceRepoId = download.getRepoId();
                PackageType packageType = download.getRepoId().getPackageType();

                // Apply both filters for downloads
                if (!ignoreDependencySource(sourceRepoId) && artifactFilterPromotion.accepts(download)) {

                    switch (packageType) {
                        case MAVEN:
                        case NPM:
                            // Determine dependencies target (prefer first Maven/NPM found)
                            if (dependenciesTarget == null) {
                                dependenciesTarget = getSharedImportsPromotionTarget(
                                        packageType,
                                        promotionTargetsCache);
                            }
                            filteredDownloads.add(download);
                            break;

                        case GENERIC:
                            // Generic downloads will be added as a separate module
                            // Note: Paths are already transformed by Artifactory plugin in generic-pre-promotion repo
                            filteredGenericDownloads.add(download);

                            // Set generic downloads target
                            if (genericDownloadsTarget == null) {
                                genericDownloadsTarget = RepositoryId.builder()
                                        .project(sourceRepoId.getProject())
                                        .packageType(packageType)
                                        .name(RepositoryConstants.GENERIC_DOWNLOADS)
                                        .build();
                            }
                            break;

                        default:
                            // Skip other package types
                            break;
                    }
                }
            }
        }

        // Determine module name from uploads
        // TODO: This is a bit of hack to establish a module name - the problem is that
        //       we don't know the actual top level for a multi-module project so this could
        //       end up picking the wrong one. Really the RepositoryPromoteRequest needs to
        //       pass the name in.
        String moduleName = determineModuleNameFromUploads(uploadsPackageType, filteredUploads, buildContentId);

        // Create primary TrackingReport with filtered uploads and non-generic downloads
        TrackingReport primaryReport = TrackingReport.builder()
                .uploads(filteredUploads)
                .downloads(filteredDownloads)
                .trackingID(buildContentId)
                .build();

        // Create primary Build object containing artifacts and non-generic dependencies
        org.jfrog.build.api.Build primaryBuild = org.jboss.pnc.repositorydriver.buildinfo.BuildInfoConverter
                .fromTrackingReport(
                        primaryReport,
                        configuration.getDeploymentType().toString(),
                        moduleName,
                        repositoryType,
                        buildAgentName,
                        buildAgentVersion,
                        startTime);

        // Create generic downloads Build (if there are generic downloads)
        org.jfrog.build.api.Build genericBuild = null;
        if (!filteredGenericDownloads.isEmpty()) {
            genericBuild = org.jboss.pnc.repositorydriver.buildinfo.BuildInfoConverter.createGenericDownloadsBuild(
                    filteredGenericDownloads,
                    configuration.getDeploymentType().toString(),
                    moduleName,
                    buildContentId,
                    buildAgentName,
                    buildAgentVersion,
                    startTime);
        }

        logger.info(
                "Created BuildInfo {} with {} artifacts, {} dependencies, and {} generic downloads. "
                        + "Artifacts target: {}, Dependencies target: {}, Generic downloads target: {}",
                moduleName,
                filteredUploads.size(),
                filteredDownloads.size(),
                filteredGenericDownloads.size(),
                artifactsTarget != null ? artifactsTarget.getPath() : "none",
                dependenciesTarget != null ? dependenciesTarget.getPath() : "none",
                genericDownloadsTarget != null ? genericDownloadsTarget.getPath() : "none");

        return new BuildInfoPromotion(
                primaryBuild,
                artifactsTarget,
                dependenciesTarget,
                genericBuild,
                genericDownloadsTarget);
    }

    /**
     * Determines the module name for a BuildInfo from uploads only.
     * This ensures consistent module naming across all BuildInfo objects (including shared-imports).
     *
     * @param packageType the package type of the uploads
     * @param uploads the filtered upload entries
     * @param trackingId the tracking ID for fallback
     * @return the module name for the BuildInfo
     */
    private String determineModuleNameFromUploads(
            PackageType packageType,
            Set<TrackedEntry> uploads,
            String trackingId) {
        if (packageType == null) {
            // No uploads, use tracking ID
            return trackingId;
        }

        switch (packageType) {
            case MAVEN:
                // Extract GAV from first Maven artifact
                return extractMavenModuleNameFromUploads(uploads, trackingId);

            case NPM:
                // Use NPM package identifier
                return extractNpmModuleNameFromUploads(uploads, trackingId);

            case GENERIC:
                // TODO: Improve generic module naming strategy
                return "generic-" + trackingId;

            default:
                // Should never reach here with current PackageType enum values
                throw new IllegalArgumentException("Unsupported package type: " + packageType);
        }
    }

    /**
     * Extracts Maven module name (GAV) from uploads only.
     *
     * TODO: ### This should be replaced ; the promote request should pass the module name
     * removing the need for this.
     *
     * @param uploads the upload entries
     * @param trackingId fallback tracking ID
     * @return Maven GAV or fallback name
     */
    private String extractMavenModuleNameFromUploads(Set<TrackedEntry> uploads, String trackingId) {
        for (TrackedEntry upload : uploads) {
            ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(upload.getPath());
            if (pathInfo == null) {
                pathInfo = ArtifactPathInfo.parse(upload.getPath() + MAVEN_SUBSTITUTE_EXTENSION);
            }
            if (pathInfo != null) {
                return pathInfo.getProjectId().toString(); // Returns groupId:artifactId:version
            }
        }

        // Fallback
        return "maven-" + trackingId;
    }

    /**
     * Extracts NPM module name from uploads only.
     *
     * @param uploads the upload entries
     * @param trackingId fallback tracking ID
     * @return NPM package identifier or fallback name
     */
    private String extractNpmModuleNameFromUploads(Set<TrackedEntry> uploads, String trackingId) {
        for (TrackedEntry upload : uploads) {
            NpmPackagePathInfo npmPathInfo = NpmPackagePathInfo.parse(upload.getPath());
            if (npmPathInfo != null) {
                NpmPackageRef packageRef = new NpmPackageRef(npmPathInfo.getName(), npmPathInfo.getVersion());
                return packageRef.toString(); // Returns package@version
            }
        }

        // Fallback
        return "npm-" + trackingId;
    }

    /**
     * Computes identifier string for an artifact. If the download path is valid for a package-type specific artifact it
     * creates the identifier accordingly.
     *
     * @param transfer the download or upload that we want to generate identifier for
     * @return generated identifier
     */
    private String computeIdentifier(final TrackedEntry transfer) {
        String identifier = null;

        switch (transfer.getRepoId().getPackageType()) {
            case MAVEN:
                ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(transfer.getPath());

                if (pathInfo == null) {
                    // NCL-7238: handle cases where url has no file extension. we add the extension
                    // MAVEN_SUBSTITUTE_EXTENSION and see if that helps to parse the pathInfo. Otherwise this causes
                    // nasty artifact duplicates
                    pathInfo = ArtifactPathInfo.parse(transfer.getPath() + MAVEN_SUBSTITUTE_EXTENSION);
                }
                if (pathInfo != null) {
                    ArtifactRef aref = new SimpleArtifactRef(
                            pathInfo.getProjectId(),
                            pathInfo.getType(),
                            pathInfo.getClassifier());
                    identifier = aref.toString();
                } else if (transfer.getPath() != null && transfer.getPath().endsWith(".rpm")) {
                    GAPVQ gapvq = parseRpmPathToGAPVQ(transfer.getPath());
                    if (gapvq != null) {
                        identifier = gapvq.identifier();
                    }
                }
                break;

            case NPM:
                NpmPackagePathInfo npmPathInfo = NpmPackagePathInfo.parse(transfer.getPath());
                if (npmPathInfo != null) {
                    NpmPackageRef packageRef = new NpmPackageRef(npmPathInfo.getName(), npmPathInfo.getVersion());
                    identifier = packageRef.toString();
                }
                break;

            case GENERIC:
                // handle generic downloads along with other invalid download paths for other package types
                break;

            default:
                // do not do anything by default
                logger.warn(
                        "Package type {} is not handled by repository session.",
                        transfer.getRepoId().getPackageType());
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
     * Computes purl string for an artifact.
     *
     * @param transfer the download or upload that we want to generate identifier for
     * @param filename previously computed filename to avoid computing it again and maybe differently
     * @return generated purl
     */
    private String computePurl(final TrackedEntry transfer, final String filename) {
        String purl = null;

        try {
            switch (transfer.getRepoId().getPackageType()) {
                case MAVEN:

                    ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(transfer.getPath());
                    if (pathInfo == null) {
                        // NCL-7238: handle cases where url has no file extension. we add the extension
                        // MAVEN_SUBSTITUTE_EXTENSION and see if that helps to parse the pathInfo. Otherwise this causes
                        // nasty artifact duplicates
                        pathInfo = ArtifactPathInfo.parse(transfer.getPath() + MAVEN_SUBSTITUTE_EXTENSION);
                    }
                    if (pathInfo != null) {
                        // See https://github.com/package-url/purl-spec/blob/master/PURL-TYPES.rst#maven
                        PackageURLBuilder purlBuilder = PackageURLBuilder.aPackageURL()
                                .withType(PackageURL.StandardTypes.MAVEN)
                                .withNamespace(pathInfo.getProjectId().getGroupId())
                                .withName(pathInfo.getProjectId().getArtifactId())
                                .withVersion(pathInfo.getVersion())
                                .withQualifier(
                                        "type",
                                        StringUtils.isEmpty(pathInfo.getType()) ? "jar" : pathInfo.getType());

                        if (!StringUtils.isEmpty(pathInfo.getClassifier())) {
                            purlBuilder.withQualifier("classifier", pathInfo.getClassifier());
                        }
                        purl = purlBuilder.build().toString();
                    } else if (transfer.getPath() != null && transfer.getPath().endsWith(".rpm")) {
                        GAPVQ gapvq = parseRpmPathToGAPVQ(transfer.getPath());
                        if (gapvq != null) {
                            purl = PackageURLBuilder.aPackageURL()
                                    .withType(PackageURL.StandardTypes.RPM)
                                    .withNamespace(gapvq.groupId)
                                    .withName(gapvq.artifactId)
                                    .withVersion(gapvq.qualifier) // quarlifier will contain more detailed version of
                                    // rpm
                                    .withQualifier("type", "rpm")
                                    .build()
                                    .toString();
                        }
                    }
                    break;

                case NPM:

                    NpmPackagePathInfo npmPathInfo = NpmPackagePathInfo.parse(transfer.getPath());
                    if (npmPathInfo != null) {
                        // See https://github.com/package-url/purl-spec/blob/master/PURL-TYPES.rst#npm
                        PackageURLBuilder purlBuilder = PackageURLBuilder.aPackageURL()
                                .withType(PackageURL.StandardTypes.NPM)
                                .withVersion(npmPathInfo.getVersion().toString());

                        String[] scopeAndName = npmPathInfo.getName().split("/");
                        if (scopeAndName != null && scopeAndName.length > 0) {
                            if (scopeAndName.length == 1) {
                                // No scope
                                purlBuilder.withName(scopeAndName[0]);

                                purl = purlBuilder.build().toString();
                            } else if (scopeAndName.length == 2) {
                                // Scoped package
                                purlBuilder.withNamespace(scopeAndName[0]);
                                purlBuilder.withName(scopeAndName[1]);

                                purl = purlBuilder.build().toString();
                            }
                        }
                    }
                    break;

                case GENERIC:
                    // handle generic downloads along with other invalid download paths for other package types
                    break;

                default:
                    // do not do anything by default
                    logger.warn(
                            "Package type {} is not handled by repository session.",
                            transfer.getRepoId().getPackageType());
                    break;
            }

            if (purl == null) {
                purl = computeGenericPurl(
                        filename,
                        transfer.getOriginUrl(),
                        transfer.getLocalUrl(),
                        transfer.getSha256());
            }

        } catch (MalformedPackageURLException ex) {
            logger.error(
                    "Cannot calculate purl for path {}. Reason given was: {}.",
                    transfer.getPath(),
                    ex.getMessage(),
                    ex);
        }
        return purl;
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
     * Compute the purl string for a generic download, that does not match package type specific files structure. It
     * prefers to use the origin URL if it is not empty. In case it is then it uses local URL, which can never be empty,
     * it is the local file mirror in Indy. Apart from that it attaches the sha256 checksum.
     *
     * @param originUrl the origin URL of the transfer, it can be null
     * @param localUrl url where the artifact was backed up in Indy
     * @param sha256 the SHA-256 of the transfer
     * @return the generated purl
     * @throws MalformedPackageURLException
     * @see <a href="https://github.com/package-url/purl-spec/blob/master/PURL-TYPES.rst#generic">PURL-TYPES</a>
     */
    private String computeGenericPurl(String filename, String originUrl, String localUrl, String sha256)
            throws MalformedPackageURLException {
        String downloadUrl = originUrl != null ? originUrl : localUrl;
        String name = filename;
        if (Strings.isEmpty(name)) {
            name = new File(downloadUrl).getName();
        }

        PackageURLBuilder purlBuilder = PackageURLBuilder.aPackageURL()
                .withType(PackageURL.StandardTypes.GENERIC)
                .withName(name)
                .withQualifier("download_url", downloadUrl)
                .withQualifier("checksum", "sha256:" + sha256);

        return purlBuilder.build().toString();
    }

    private TargetRepository getDownloadsTargetRepository(TrackedEntry download)
            throws RepositoryDriverException {
        RepositoryId repoId = download.getRepoId();
        PackageType packageType = download.getRepoId().getPackageType();
        RepositoryType repoType = TypeConverters.toRepoType(packageType);
        String repoPath;
        String identifier = TypeConverters.toRepositoryIdentifier(repoType);

        if (repoType == RepositoryType.MAVEN || repoType == RepositoryType.NPM) {
            if (ignoreDependencySource(repoId)) {
                repoPath = repoId.getPath();
            } else {
                repoPath = download.getRepoId().getProject() + "-" + TypeConverters.toRepositoryTypeString(repoType)
                        + "-imports";
            }
            logger.info(
                    "### getDownloadsTargetRepo::ignoreDepSource {} and repoPath {} repoId {}",
                    ignoreDependencySource(repoId),
                    repoPath,
                    repoId);
        } else if (repoType == RepositoryType.GENERIC_PROXY) {
            repoPath = download.getRepoId().getProject() + "-" + RepositoryConstants.GENERIC_DOWNLOADS;
        } else {
            throw new RepositoryDriverException(
                    "Repository type " + repoType + " is not supported by Indy repo manager driver.");
        }

        logger.info("### getDownloadsTargetRepository::repoId {} repoType {} repoPath {} ", repoId, repoType, repoPath);

        return TargetRepository.builder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(false)
                .build();
    }

    /**
     * Check artifact for any validation errors. If there are constraint violations, then a RepositoryManagerException
     * is thrown. Otherwise the artifact is returned.
     *
     * @param artifact to validate
     * @return the same artifact
     * @throws RepositoryDriverException if there are constraint violations
     */
    private RepositoryArtifact validateArtifact(RepositoryArtifact artifact) throws RepositoryDriverException {
        Set<ConstraintViolation<RepositoryArtifact>> violations = validator.validate(artifact);
        if (!violations.isEmpty()) {
            throw new RepositoryDriverException(
                    "Repository manager returned invalid artifact: " + artifact.toString()
                            + " Constraint Violations: %s",
                    violations);
        }
        return artifact;
    }

    private TargetRepository getUploadsTargetRepository(
            RepositoryType repoType,
            BuildCategory buildCategory,
            boolean tempBuild)
            throws RepositoryDriverException {
        if (repoType != RepositoryType.MAVEN && repoType != RepositoryType.NPM) {
            throw new RepositoryDriverException(
                    "Repository type " + repoType + " is not supported for uploads by repo manager driver.");
        }

        String target = getBuildPromotionTarget(buildCategory, tempBuild);
        String identifier = TypeConverters.toRepositoryIdentifier(repoType);
        String repoPath = configuration.getDeploymentType() + "-" + target;

        return TargetRepository.builder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(tempBuild)
                .build();
    }

    private RepositoryId getSharedImportsPromotionTarget(
            PackageType packageType,
            Map<PackageType, RepositoryId> promotionTargetsCache) {
        if (!promotionTargetsCache.containsKey(packageType)) {
            RepositoryId repositoryId = RepositoryId.builder()
                    .project(configuration.getDeploymentType().toString())
                    .packageType(packageType)
                    .name(packageType == PackageType.MAVEN ? MVN_SHARED_IMPORTS_ID : NPM_SHARED_IMPORTS_ID)
                    .build();
            promotionTargetsCache.put(packageType, repositoryId);
        }
        return promotionTargetsCache.get(packageType);
    }

    private String getBuildPromotionTarget(BuildCategory buildCategory, boolean tempBuild) {
        return tempBuild ? configuration.getTempBuildPromotionTarget(buildCategory)
                : configuration.getBuildPromotionTarget(buildCategory);
    }

    /**
     * Helper DTO method for parseRpmPathToGAPVQ
     */
    @Builder
    static class GAPVQ {
        String groupId;
        String artifactId;
        String packageType;
        String version;
        String qualifier;

        public String identifier() {
            return String.format("%s:%s:%s:%s:%s", groupId, artifactId, packageType, version, qualifier);
        }
    }

    static GAPVQ parseRpmPathToGAPVQ(String path) {

        // sanity check, the path needs to end with '.rpm'
        if (!path.endsWith(".rpm")) {
            return null;
        }

        // code inspired from Indy codebase
        String groupRegex = "(([^/]+/)*[^/]+)"; // group 1~2
        String artifactRegex = "([^/]+)"; // group 3
        String versionRawRegex = "(([^/]+)(-SNAPSHOT)?)"; // group 4~6
        String rpmFilenameRegex = "([^/]+\\.rpm)"; // group 7

        int groupIdGroup = 1;
        int artifactIdGroup = 3;
        int versionGroup = 4;
        int rpmFilenameGroup = 7;

        // Regex explanation:
        // - matches the hyphen we want to split on
        // (?= ... ) is a positive lookahead, which checks for the pattern
        // [^-]* matches zero or more characters that are NOT a hyphen
        // - matches the last hyphen
        // [^-]*$ matches zero or more characters that are not a hyphen, up to the end of the string
        String rpmVersionRegex = "-(?=[^-]*-[^-]*$)";

        String artifactPathRegex = "/?" + groupRegex + "/" + artifactRegex + "/" + versionRawRegex + "/"
                + rpmFilenameRegex;

        Matcher matcher = Pattern.compile(artifactPathRegex).matcher(path.replace('\\', '/'));

        if (!matcher.matches()) {
            return null;
        }

        String groupId = matcher.group(groupIdGroup).replace('/', '.');
        String artifactId = matcher.group(artifactIdGroup);
        String version = matcher.group(versionGroup);
        String rpmFilename = matcher.group(rpmFilenameGroup);

        // The limit of 2 ensures the split is performed only once.
        String[] rpmFilenameParts = rpmFilename.split(rpmVersionRegex, 2);
        String qualifier = rpmFilenameParts[1].replace(".rpm", "");
        return GAPVQ.builder()
                .groupId(groupId)
                .artifactId(artifactId)
                .packageType("rpm")
                .version(version)
                .qualifier(qualifier)
                .build();
    }
}
