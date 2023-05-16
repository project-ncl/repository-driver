package org.jboss.pnc.repositorydriver;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;

import org.apache.commons.lang.StringUtils;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.commonjava.atlas.npm.ident.util.NpmPackagePathInfo;
import org.commonjava.indy.client.core.module.IndyContentClientModule;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.jboss.pnc.api.constants.ReposiotryIdentifier;
import org.jboss.pnc.api.enums.ArtifactQuality;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.RepositoryType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryArtifact;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.common.Strings;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilter;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterArchive;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterDatabase;
import org.jboss.pnc.repositorydriver.artifactfilter.ArtifactFilterPromotion;
import org.jboss.pnc.repositorydriver.artifactfilter.PatternsList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.jboss.pnc.repositorydriver.ArchiveDownloadEntry.fromTrackedContentEntry;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.SHARED_IMPORTS_ID;

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
    IndyContentClientModule indyContentModule;

    private PatternsList ignoredRepoPatterns;

    @PostConstruct
    public void init() {
        if (configuration.getIgnoredRepoPatterns().isPresent()) {
            ignoredRepoPatterns = new PatternsList(configuration.getIgnoredRepoPatterns().get());
        } else {
            ignoredRepoPatterns = new PatternsList(Collections.emptyList());
        }
    }

    @WithSpan()
    public List<RepositoryArtifact> collectDownloadedArtifacts(
            @SpanAttribute(value = "report") TrackedContentDTO report,
            @SpanAttribute(value = "filter") ArtifactFilter filter) throws RepositoryDriverException {
        Set<TrackedContentEntryDTO> downloads = report.getDownloads();
        if (downloads == null) {
            return Collections.emptyList();
        }

        List<RepositoryArtifact> deps = new ArrayList<>(downloads.size());
        for (TrackedContentEntryDTO download : downloads) {
            if (filter.accepts(download)) {
                String path = download.getPath();
                StoreKey storeKey = download.getStoreKey();
                String identifier = computeIdentifier(download);
                String purl = computePurl(download);

                logger.info("Recording download: {}", identifier);

                String originUrl = download.getOriginUrl();
                if (originUrl == null) {
                    // this is from a hosted repository, either shared-imports or a build, or something like that.
                    originUrl = download.getLocalUrl();
                }

                TargetRepository targetRepository = getDownloadsTargetRepository(download);

                // ignored dependency sources for promotion are the internal ones, so those artifacts are built inhouse
                ArtifactQuality quality = ignoreDependencySource(storeKey) ? ArtifactQuality.NEW
                        : ArtifactQuality.IMPORTED;
                RepositoryArtifact.Builder artifactBuilder = RepositoryArtifact.builder()
                        .md5(download.getMd5())
                        .sha1(download.getSha1())
                        .sha256(download.getSha256())
                        .size(download.getSize())
                        .deployPath(path)
                        .originUrl(originUrl)
                        .importDate(Instant.now())
                        .filename(new File(path).getName())
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
     * Checks if given store is ignored for dependencies promotion.
     *
     * @param storeKey evaluated store key
     * @return true if the given store is ignored, false otherwise
     */
    private boolean ignoreDependencySource(StoreKey storeKey) {
        String strSK = storeKey.toString();
        return matchesOne(strSK, ignoredRepoPatterns);
    }

    /**
     * Checks if the given string matches one of the patterns.
     *
     * @param string the string
     * @param patterns the patterns list
     * @return true if there is a matching pattern, false otherwise
     */
    private boolean matchesOne(String string, PatternsList patterns) {
        if (patterns != null) {
            for (Pattern pattern : patterns.getPatterns()) {
                if (pattern.matcher(string).matches()) {
                    return true;
                }
            }
        }
        return false;
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
            @SpanAttribute(value = "report") TrackedContentDTO report,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "buildCategory") BuildCategory buildCategory) throws RepositoryDriverException {

        Set<TrackedContentEntryDTO> uploads = report.getUploads();
        if (uploads == null) {
            return Collections.emptyList();
        }
        List<RepositoryArtifact> artifacts = new ArrayList<>(uploads.size());
        for (TrackedContentEntryDTO upload : uploads) {
            String path = upload.getPath();
            StoreKey storeKey = upload.getStoreKey();

            if (artifactFilterDatabase.accepts(upload)) {
                String identifier = computeIdentifier(upload);
                String purl = computePurl(upload);

                logger.info("Recording upload: {}", identifier);
                RepositoryType repoType = TypeConverters.toRepoType(storeKey.getPackageType());
                TargetRepository targetRepository = getUploadsTargetRepository(repoType, tempBuild);

                RepositoryArtifact artifact = RepositoryArtifact.builder()
                        .md5(upload.getMd5())
                        .sha1(upload.getSha1())
                        .sha256(upload.getSha256())
                        .size(upload.getSize())
                        .deployPath(upload.getPath())
                        .filename(new File(path).getName())
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
            @SpanAttribute(value = "report") TrackedContentDTO report,
            @SpanAttribute(value = "genericRepos") Collection<StoreKey> genericRepos) {
        PromotionPaths promotionPaths = new PromotionPaths();
        Set<TrackedContentEntryDTO> downloads = report.getDownloads();
        if (downloads == null) {
            return promotionPaths;
        }
        Map<String, StoreKey> promotionTargetsCache = new HashMap<>();
        for (TrackedContentEntryDTO download : downloads) {
            String path = download.getPath();
            StoreKey source = download.getStoreKey();
            String packageType = source.getPackageType();
            if (!ignoreDependencySource(source) && artifactFilterPromotion.accepts(download)) {
                StoreKey target;
                // this has not been captured, so promote it.
                switch (packageType) {
                    case MAVEN_PKG_KEY:
                    case NPM_PKG_KEY:
                        target = getSharedImportsPromotionTarget(packageType, promotionTargetsCache);
                        promotionPaths.add(source, target, path);
                        break;

                    case GENERIC_PKG_KEY:
                        String remoteName = source.getName();
                        genericRepos.add(source);
                        String hostedName = getGenericHostedRepoName(remoteName);
                        target = new StoreKey(packageType, StoreType.hosted, hostedName);
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
            @SpanAttribute(value = "report") TrackedContentDTO report) throws RepositoryDriverException {
        Set<TrackedContentEntryDTO> downloads = report.getDownloads();
        if (downloads == null) {
            return Collections.emptyList();
        }

        List<ArchiveDownloadEntry> deps = new ArrayList<>(downloads.size());
        for (TrackedContentEntryDTO download : downloads) {
            if (artifactFilterArchive.accepts(download)) {
                TargetRepository targetRepository = getDownloadsTargetRepository(download);
                ArchiveDownloadEntry entry = fromTrackedContentEntry(download, targetRepository);
                deps.add(entry);
            }
        }
        deps.sort(Comparator.comparing(ArchiveDownloadEntry::getStoreKey));
        return deps;
    }

    @WithSpan()
    public PromotionPaths collectUploadsPromotions(
            @SpanAttribute(value = "report") TrackedContentDTO report,
            @SpanAttribute(value = "tempBuild") boolean tempBuild,
            @SpanAttribute(value = "repositoryType") RepositoryType repositoryType,
            @SpanAttribute(value = "buildContentId") String buildContentId) {
        PromotionPaths promotionPaths = new PromotionPaths();
        Set<TrackedContentEntryDTO> uploads = report.getUploads();
        if (uploads == null) {
            return promotionPaths;
        }
        for (TrackedContentEntryDTO upload : uploads) {
            String path = upload.getPath();
            if (artifactFilterPromotion.accepts(upload)) {
                String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);
                StoreKey source = new StoreKey(packageType, StoreType.hosted, buildContentId);
                StoreKey target = new StoreKey(packageType, StoreType.hosted, getBuildPromotionTarget(tempBuild));
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
    private String computeIdentifier(final TrackedContentEntryDTO transfer) {
        String identifier = null;

        switch (transfer.getStoreKey().getPackageType()) {
            case MAVEN_PKG_KEY:
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
     * Computes purl string for an artifact.
     *
     * @param transfer the download or upload that we want to generate identifier for
     * @return generated purl
     */
    private String computePurl(final TrackedContentEntryDTO transfer) {
        String purl = null;

        try {
            switch (transfer.getStoreKey().getPackageType()) {
                case MAVEN_PKG_KEY:

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
                    }
                    break;

                case NPM_PKG_KEY:

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

            if (purl == null) {
                purl = computeGenericPurl(
                        transfer.getPath(),
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
     * it is the local file mirror in Indy. After that it attaches the sha256 separated by a pipe.
     *
     * @param originUrl the origin URL of the transfer, it can be null
     * @param localUrl url where the artifact was backed up in Indy
     * @param sha256 the SHA-256 of the transfer
     * @return the generated purl
     * @throws MalformedPackageURLException
     */
    private String computeGenericPurl(String path, String originUrl, String localUrl, String sha256)
            throws MalformedPackageURLException {
        // See https://github.com/package-url/purl-spec/blob/master/PURL-TYPES.rst#generic
        String downloadUrl = originUrl != null ? originUrl : localUrl;
        String name = new File(path).getName();
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

    private TargetRepository getDownloadsTargetRepository(TrackedContentEntryDTO download)
            throws RepositoryDriverException {
        String identifier;
        String repoPath;
        StoreKey source = download.getStoreKey();
        RepositoryType repoType = TypeConverters.toRepoType(source.getPackageType());
        if (repoType == RepositoryType.MAVEN || repoType == RepositoryType.NPM) {
            identifier = "indy-" + repoType.name().toLowerCase();
            repoPath = getTargetRepositoryPath(download, indyContentModule);
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

        return TargetRepository.builder()
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
        if (ignoreDependencySource(sk)) {
            result = "/api/" + content.contentPath(sk);
        } else {
            result = "/api/" + content.contentPath(new StoreKey(packageType, StoreType.hosted, SHARED_IMPORTS_ID));
        }
        return result;
    }

    private String getGenericTargetRepositoryPath(StoreKey source) {
        return "/api/content/generic-http/hosted/" + getGenericHostedRepoName(source.getName());
    }

    /**
     * For a remote generic http repo/group computes matching hosted repo name.
     *
     * @param remoteName the remote repo name
     * @return computed hosted repo name
     */
    private String getGenericHostedRepoName(String remoteName) {
        String hostedName;
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

        String repoPath = "/api/" + indyContentModule.contentPath(storeKey);
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

    private StoreKey getSharedImportsPromotionTarget(String packageType, Map<String, StoreKey> promotionTargetsCache) {
        if (!promotionTargetsCache.containsKey(packageType)) {
            StoreKey storeKey = new StoreKey(packageType, StoreType.hosted, SHARED_IMPORTS_ID);
            promotionTargetsCache.put(packageType, storeKey);
        }
        return promotionTargetsCache.get(packageType);
    }

    private String getBuildPromotionTarget(boolean tempBuild) {
        return tempBuild ? configuration.getTempBuildPromotionTarget() : configuration.getBuildPromotionTarget();
    }
}
