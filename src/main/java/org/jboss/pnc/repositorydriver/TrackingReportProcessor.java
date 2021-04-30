package org.jboss.pnc.repositorydriver;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
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
import org.jboss.pnc.constants.ReposiotryIdentifier;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.TargetRepository;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.pnc.enums.BuildCategory;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.repositorydriver.constants.Checksum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.SHARED_IMPORTS_ID;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class TrackingReportProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TrackingReportProcessor.class);

    private static final Logger userLog = LoggerFactory.getLogger("org.jboss.pnc._userlog_.repository-driver");

    @Inject
    ArtifactFilter artifactFilter;

    @Inject
    Validator validator;

    @Inject
    Configuration configuration;

    @Inject
    IndyContentClientModule indyContentModule;

    public List<Artifact> collectDownloadedArtifacts(TrackedContentDTO report) throws RepositoryDriverException {
        //TODO add log with duration of this method
        Set<TrackedContentEntryDTO> downloads = report.getDownloads();

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

                TargetRepository targetRepository = getDownloadsTargetRepository(download);

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

    /**
     * Return list of output artifacts for promotion.
     *
     * @return List of output artifacts meta data
     * @throws RepositoryDriverException In case of a client API transport error or an error during promotion of
     *         artifacts
     */
    public List<Artifact> collectUploadedArtifacts(TrackedContentDTO report, boolean tempBuild, BuildCategory buildCategory)
            throws RepositoryDriverException {

        List<Artifact> artifacts = new ArrayList<>();

        userLog.info("Processing built artifacts"); // TODO duration
        StopWatch stopWatch = StopWatch.createStarted();

        for (TrackedContentEntryDTO upload : report.getUploads()) {
            String path = upload.getPath();
            StoreKey storeKey = upload.getStoreKey();

            if (artifactFilter.acceptsForData(upload)) {
                String identifier = computeIdentifier(upload);

                logger.info("Recording upload: {}", identifier);

                RepositoryType repoType = TypeConverters.toRepoType(storeKey.getPackageType());
                TargetRepository targetRepository = getUploadsTargetRepository(repoType, tempBuild);

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
                        .buildCategory(buildCategory)
                        .build();

                artifacts.add(validateArtifact(artifact));
            }
        }
        logger.info("END: Process artifacts uploaded from build, took {} seconds", stopWatch.getTime(TimeUnit.SECONDS));
        return artifacts;
    }

    public PromotionPaths collectDownloadsPromotions(TrackedContentDTO report) {
        Set<TrackedContentEntryDTO> downloads = report.getDownloads();
        PromotionPaths promotionPaths = new PromotionPaths();
        Map<String, StoreKey> promotionTargetsCache = new HashMap<>();
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
                        target = getSharedImportsPromotionTarget(packageType, promotionTargetsCache);
                        promotionPaths.add(source, target, path);
                        if (MAVEN_PKG_KEY.equals(packageType) && !isChecksum(path)) {
                            // add the standard checksums to ensure, they are promoted (Maven usually uses only one, so
                            // the other would be missing) but avoid adding checksums of checksums.
                            promotionPaths.add(source, target, path + ".md5");
                            promotionPaths.add(source, target, path + ".sha1");
                        }
                        break;

                    case GENERIC_PKG_KEY:
                        String remoteName = source.getName();
                        String hostedName = getGenericHostedRepoName(remoteName);
                        target = new StoreKey(packageType, StoreType.hosted, hostedName);
                        promotionPaths.add(source, target, path + ".sha1");
                        break;

                    default:
                        // do not promote anything else anywhere
                        break;
                }
            }
        }
        return promotionPaths;
    }

    public PromotionPaths collectUploadsPromotions(
            TrackedContentDTO report,
            boolean tempBuild,
            RepositoryType repositoryType,
            String buildContentId) {
        PromotionPaths promotionPaths = new PromotionPaths();
        for (TrackedContentEntryDTO upload : report.getUploads()) {
            String path = upload.getPath();
            StoreKey storeKey = upload.getStoreKey();
            if (artifactFilter.acceptsForPromotion(upload, false)) {
                String packageType = TypeConverters.getIndyPackageTypeKey(repositoryType);
                StoreKey source = new StoreKey(packageType, StoreType.hosted, buildContentId);
                StoreKey target = new StoreKey(packageType, StoreType.hosted, getBuildPromotionTarget(tempBuild));
                promotionPaths.add(source, target, path);
                if (MAVEN_PKG_KEY.equals(storeKey.getPackageType()) && !isChecksum(path)) {
                    // add the standard checksums to ensure, they are promoted (Maven usually uses only one, so
                    // the other would be missing) but avoid adding checksums of checksums.
                    promotionPaths.add(source, target, path + ".md5");
                    promotionPaths.add(source, target, path + ".sha1");
                }
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

    private TargetRepository getDownloadsTargetRepository(
            TrackedContentEntryDTO download) throws RepositoryDriverException {
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

    private String getGenericTargetRepositoryPath(StoreKey source) {
        return "/api/content/generic-http/hosted/" + getGenericHostedRepoName(source.getName());
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

    private TargetRepository getUploadsTargetRepository(
            RepositoryType repoType,
            boolean tempBuild) throws RepositoryDriverException {

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
        return TargetRepository.refBuilder()
                .identifier(identifier)
                .repositoryType(repoType)
                .repositoryPath(repoPath)
                .temporaryRepo(tempBuild)
                .build();
    }

    private ArtifactQuality getArtifactQuality(boolean isTempBuild) {
        if (isTempBuild) {
            return ArtifactQuality.TEMPORARY;
        } else {
            return ArtifactQuality.NEW;
        }
    }

    private boolean isChecksum(String path) {
        String suffix = StringUtils.substringAfterLast(path, ".");
        return Checksum.suffixes.contains(suffix);
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
