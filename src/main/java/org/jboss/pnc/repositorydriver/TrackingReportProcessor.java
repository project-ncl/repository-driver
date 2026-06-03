package org.jboss.pnc.repositorydriver;

import static org.jboss.pnc.repositorydriver.ArchiveDownloadEntry.fromTrackedEntry;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.SHARED_IMPORTS_ID;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.enums.ArtifactQuality;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.api.trackingservice.dto.PackageType;
import org.jboss.pnc.api.trackingservice.dto.TrackedEntry;
import org.jboss.pnc.api.trackingservice.dto.TrackingReport;
import org.jboss.pnc.common.Strings;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilter;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterArchive;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterPromotion;
import org.jboss.pnc.repositorydriver.artifactfilter.PatternsList;
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

    //    @Inject
    //    IndyContentClientModule indyContentModule;

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

                logger.info("Recording download: {}", identifier);

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
            PackageType packageType = upload.getPackageType();

            if (artifactFilterDatabase.accepts(upload)) {
                String identifier = computeIdentifier(upload);
                String filename = new File(path).getName();
                String purl = computePurl(upload, filename);

                logger.info("Recording upload: {}", identifier);
                RepositoryType repoType = TypeConverters.toRepoType(packageType);
                TargetRepository targetRepository = getUploadsTargetRepository(repoType, tempBuild);

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
    public PromotionPaths collectDownloadsPromotions(
            @SpanAttribute(value = "report") TrackingReport report,
            @SpanAttribute(value = "genericRepos") Collection<RepositoryKey> genericRepos) {
        PromotionPaths promotionPaths = new PromotionPaths();
        Set<TrackedEntry> downloads = report.getDownloads();
        if (downloads == null) {
            return promotionPaths;
        }
        Map<PackageType, RepositoryKey> promotionTargetsCache = new HashMap<>();
        for (TrackedEntry download : downloads) {
            String path = download.getPath();
            RepositoryId sourceRepoId = download.getRepoId();
            PackageType packageType = download.getPackageType();
            if (!ignoreDependencySource(sourceRepoId) && artifactFilterPromotion.accepts(download)) {
                RepositoryKey source = new RepositoryKey(sourceRepoId, packageType, false, false);
                RepositoryKey target;
                // this has not been captured, so promote it.
                switch (packageType) {
                    case MVN:
                    case NPM:
                        // TODO: ### Fix and change this.
                        target = getSharedImportsPromotionTarget(packageType, promotionTargetsCache);
                        promotionPaths.add(source, target, path);
                        break;

                    case GENERIC:
                        String remoteName = sourceRepoId.getName();
                        genericRepos.add(source);
                        String hostedName = getGenericHostedRepoName(remoteName);
                        RepositoryId targetRepoId = RepositoryId.builder()
                                .project(sourceRepoId.getProject())
                                .name(hostedName)
                                .build();
                        target = new RepositoryKey(targetRepoId, packageType, false, false);
                        promotionPaths.add(source, target, path);
                        break;

                    default:
                        // do not promote anything else anywhere
                        break;
                }
            }
        }
        return promotionPaths;
    }

    @WithSpan()
    public List<ArchiveDownloadEntry> collectArchivalArtifacts(
            @SpanAttribute(value = "report") TrackingReport report) throws RepositoryDriverException {
        logger.warn("### collectArchivalArtifacts::start {} ", report);
        Set<TrackedEntry> downloads = report.getDownloads();
        if (downloads == null) {
            return Collections.emptyList();
        }

        List<ArchiveDownloadEntry> deps = new ArrayList<>(downloads.size());
        for (TrackedEntry download : downloads) {
            logger.warn(
                    "### collectArchivalArtifacts::accepts {} is {}",
                    download,
                    artifactFilterArchive.accepts(download));
            if (artifactFilterArchive.accepts(download)) {
                // TODO: Need to change this - likely just make getDownloadsTargetRepository
                //    return a repositoryid to represent the changed repository
                TargetRepository targetRepository = getDownloadsTargetRepository(download);
                RepositoryId newId = RepositoryId.builder()
                        .project(download.getRepoId().getProject())
                        .name(targetRepository.getRepositoryPath())
                        .build();
                logger.warn("### collectArchivalArtifacts::newId {}", newId);

                ArchiveDownloadEntry entry = fromTrackedEntry(download, targetRepository);
                deps.add(entry);
            }
        }
        deps.sort(Comparator.comparing(ArchiveDownloadEntry::getRepositoryId));
        return deps;
    }

    @WithSpan()
    public PromotionPaths collectUploadsPromotions(
            @SpanAttribute(value = "report") TrackingReport report,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "repositoryType") RepositoryType repositoryType,
            @SpanAttribute(value = "buildContentId") String buildContentId) {
        PromotionPaths promotionPaths = new PromotionPaths();
        Set<TrackedEntry> uploads = report.getUploads();
        if (uploads == null) {
            return promotionPaths;
        }
        for (TrackedEntry upload : uploads) {
            String path = upload.getPath();
            if (artifactFilterPromotion.accepts(upload)) {
                PackageType packageType = TypeConverters.toPackageType(repositoryType);
                // TODO: ### Project value for RepositoryId - using deployment type as project identifier
                RepositoryId sourceRepoId = RepositoryId.builder()
                        .project(configuration.getDeploymentType().toString())
                        .name(buildContentId)
                        .build();
                RepositoryId targetRepoId = RepositoryId.builder()
                        .project(configuration.getDeploymentType().toString())
                        .name(getBuildPromotionTarget(tempBuild))
                        .build();
                RepositoryKey source = new RepositoryKey(sourceRepoId, packageType, false, tempBuild);
                RepositoryKey target = new RepositoryKey(targetRepoId, packageType, false, tempBuild);
                promotionPaths.add(source, target, path);
            }
        }
        return promotionPaths;
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

        switch (transfer.getPackageType()) {
            case MVN:
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
                        transfer.getPackageType());
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
            switch (transfer.getPackageType()) {
                case MVN:

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
                            transfer.getPackageType());
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
        String identifier;
        String repoPath;
        RepositoryId repoId = download.getRepoId();
        PackageType packageType = download.getPackageType();
        RepositoryType repoType = TypeConverters.toRepoType(packageType);

        // Use new configuration-based approach
        //        String project = repoId.getProject() != null ? repoId.getProject() : repoId.getName();
        repoPath = ArtifactoryUtils.parseDownloadTargetRepository(
                configuration.getDownloadTargetRepository(),
                repoId.getName(),
                repoType,
                download.getOriginUrl());

        logger.info("### repoType {} repoPath {} ", repoType, repoPath);
        // Extract identifier from originUrl (hostname only)
        identifier = "unknown";
        if (download.getOriginUrl() != null && !download.getOriginUrl().isEmpty()) {
            try {
                java.net.URL url = new java.net.URL(download.getOriginUrl());
                identifier = url.getHost();
            } catch (java.net.MalformedURLException e) {
            }
        }

        // OLD:
        //         if (repoType == RepositoryType.MAVEN || repoType == RepositoryType.NPM) {
        //     identifier = "indy-" + repoType.name().toLowerCase();
        //     repoPath = getTargetRepositoryPath(download, indyContentModule);
        // } else if (repoType == RepositoryType.GENERIC_PROXY) {
        //     identifier = "indy-http";
        //     repoPath = getGenericTargetRepositoryPath(repoId);
        // } else {
        //     throw new RepositoryDriverException(
        //             "Repository type " + repoType + " is not supported by Indy repo manager driver.");
        // }

        if (!repoPath.endsWith("/")) {
            repoPath += '/';
        }

        return TargetRepository.builder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(false)
                .build();
    }

    /*
     * Commented out - Indy-specific code no longer used
     * private String getTargetRepositoryPath(TrackedEntry download, IndyContentClientModule content) {
     * String result;
     * RepositoryId repoId = download.getRepoId();
     * PackageType packageType = download.getPackageType();
     * String packageTypeStr = TypeConverters.getIndyPackageTypeKey(TypeConverters.toRepoType(packageType));
     * if (ignoreDependencySource(repoId)) {
     * StoreKey sk = new StoreKey(packageTypeStr, StoreType.hosted, repoId.getName());
     * result = "/api/" + content.contentPath(sk);
     * } else {
     * result = "/api/" + content.contentPath(new StoreKey(packageTypeStr, StoreType.hosted, SHARED_IMPORTS_ID));
     * }
     * return result;
     * }
     *
     * private String getGenericTargetRepositoryPath(RepositoryId repoId) {
     * return "/api/content/generic-http/hosted/" + getGenericHostedRepoName(repoId.getName());
     * }
     */

    /**
     * For a remote generic http repo/group computes matching hosted repo name.
     *
     * @param remoteName the remote repo name
     * @return computed hosted repo name
     */
    // TODO: NYI
    private String getGenericHostedRepoName(String remoteName) {
        String hostedName;
        // TODO: ### GenericHostedName : what is this?
        if (remoteName.startsWith("r-") || remoteName.startsWith("g-")) {
            hostedName = "h-" + remoteName.substring(2);
        } else {
            logger.error(
                    "Unexpected generic http remote repo/group name {}. Using it for hosted repo "
                            + "without change, but it probably doesn't exist.",
                    remoteName);
            hostedName = remoteName;
        }
        return hostedName;
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

    private TargetRepository getUploadsTargetRepository(RepositoryType repoType, boolean tempBuild)
            throws RepositoryDriverException {

        // Use new configuration-based approach
        String project = configuration.getDeploymentType().toString();
        String buildPromotionTarget = getBuildPromotionTarget(tempBuild);
        String repoPath = ArtifactoryUtils.parseUploadsTargetRepository(
                configuration.getUploadsTargetRepository(),
                project,
                repoType,
                buildPromotionTarget);

        // Use deployment type as identifier for uploads
        String identifier = project;

        if (!repoPath.endsWith("/")) {
            repoPath += '/';
        }

        return TargetRepository.builder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(tempBuild)
                .build();
    }

    /*
     * Commented out - Old Indy-specific code
     * private TargetRepository getUploadsTargetRepository_OLD(RepositoryType repoType, boolean tempBuild)
     * throws RepositoryDriverException {
     *
     * PackageType packageType;
     * String identifier;
     * if (repoType == RepositoryType.MAVEN) {
     * packageType = PackageType.MVN;
     * identifier = ReposiotryIdentifier.INDY_MAVEN;
     * } else if (repoType == RepositoryType.NPM) {
     * packageType = PackageType.NPM;
     * identifier = ReposiotryIdentifier.INDY_NPM;
     * } else {
     * throw new RepositoryDriverException(
     * "Repository type " + repoType + " is not supported for uploads by Indy repo manager driver.");
     * }
     *
     * RepositoryId repoId = RepositoryId.builder()
     * .project(configuration.getDeploymentType().toString())
     * .name(getBuildPromotionTarget(tempBuild))
     * .build();
     * RepositoryKey repositoryKey = new RepositoryKey(repoId, packageType, false, tempBuild);
     * String repoPath = "/api/" + indyContentModule.contentPath(
     * new org.commonjava.indy.model.core.StoreKey(
     * TypeConverters.getIndyPackageTypeKey(repoType),
     * org.commonjava.indy.model.core.StoreType.hosted,
     * getBuildPromotionTarget(tempBuild)));
     * if (!repoPath.endsWith("/")) {
     * repoPath += '/';
     * }
     * return TargetRepository.builder()
     * .identifier(identifier)
     * .repositoryType(repoType)
     * .repositoryPath(repoPath)
     * .temporaryRepo(tempBuild)
     * .build();
     * }
     */

    private RepositoryKey getSharedImportsPromotionTarget(
            PackageType packageType,
            Map<PackageType, RepositoryKey> promotionTargetsCache) {
        if (!promotionTargetsCache.containsKey(packageType)) {
            RepositoryId repoId = RepositoryId.builder()
                    .project(configuration.getDeploymentType().toString())
                    .name(SHARED_IMPORTS_ID)
                    .build();
            RepositoryKey repositoryKey = new RepositoryKey(repoId, packageType, false, false);
            promotionTargetsCache.put(packageType, repositoryKey);
        }
        return promotionTargetsCache.get(packageType);
    }

    private String getBuildPromotionTarget(boolean tempBuild) {
        return tempBuild ? configuration.getTempBuildPromotionTarget() : configuration.getBuildPromotionTarget();
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
