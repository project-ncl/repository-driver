package org.jboss.pnc.repositorydriver.group;

import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.GRADLE_PLUGINS_REPO;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.REPO_UI_POSITION;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.repositorydriver.ArtifactoryUtils;
import org.jboss.pnc.repositorydriver.Configuration;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.model.RemoteRepository;
import org.jfrog.artifactory.client.model.VirtualRepository;
import org.jfrog.artifactory.client.model.repository.settings.RepositorySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Builder;
import lombok.Getter;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class ArtifactoryBuildGroupBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryBuildGroupBuilder.class);

    private Configuration configuration;
    private Artifactory artifactory;
    private RepositorySettings settings;
    private String name;
    private String description;
    private String deploymentRepository;
    private final List<String> includedRepositories = new ArrayList<>();

    // use #builder
    private ArtifactoryBuildGroupBuilder() {
    }

    public static ArtifactoryBuildGroupBuilder builder(
            Configuration configuration,
            Artifactory artifactory,
            RepositorySettings settings,
            String virtualName) {

        ArtifactoryBuildGroupBuilder buildGroupBuilder = new ArtifactoryBuildGroupBuilder();
        buildGroupBuilder.artifactory = artifactory;
        buildGroupBuilder.settings = settings;
        buildGroupBuilder.configuration = configuration;
        buildGroupBuilder.name = virtualName;
        return buildGroupBuilder;
    }

    public ArtifactoryBuildGroupBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public ArtifactoryBuildGroupBuilder addLocal(String hostKey) {
        includedRepositories.add(hostKey);
        deploymentRepository = hostKey;
        return this;
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
     */
    public ArtifactoryBuildGroupBuilder addGlobalConstituents(
            BuildType buildType,
            BuildCategory buildCategory,
            boolean tempBuild) {
        // 1. global builds artifacts
        // For constituent repositories, we only want project prefix, not the full naming template
        String projectPrefix = configuration.getArtifactoryProject();

        if (tempBuild) {
            for (String hostedTempConstituent : configuration.getBuildGroupConstituentsTempHosted(buildCategory)
                    .orElse(List.of())) {
                includedRepositories.add(
                        ArtifactoryUtils
                                .createRepositoryName(
                                        configuration.getArtifactoryProject(),
                                        buildType,
                                        hostedTempConstituent,
                                        ArtifactoryUtils.RepositoryType.LOCAL));
            }
            for (String groupTempConstituent : configuration.getBuildGroupConstituentsTempGroup(buildCategory)
                    .orElse(List.of())) {
                includedRepositories.add(
                        ArtifactoryUtils
                                .createRepositoryName(
                                        configuration.getArtifactoryProject(),
                                        buildType,
                                        groupTempConstituent,
                                        ArtifactoryUtils.RepositoryType.LOCAL));
            }
        } else {
            for (String hostedConstituent : configuration.getBuildGroupConstituentsHosted(buildCategory)
                    .orElse(List.of())) {
                includedRepositories.add(
                        ArtifactoryUtils
                                .createRepositoryName(
                                        configuration.getArtifactoryProject(),
                                        buildType,
                                        hostedConstituent,
                                        ArtifactoryUtils.RepositoryType.LOCAL));
            }
            for (String groupConstituent : configuration.getBuildGroupConstituentsGroup(buildCategory)
                    .orElse(List.of())) {
                includedRepositories.add(
                        ArtifactoryUtils
                                .createRepositoryName(
                                        configuration.getArtifactoryProject(),
                                        buildType,
                                        groupConstituent,
                                        ArtifactoryUtils.RepositoryType.LOCAL));
            }
        }

        // add build-type-specific constituents
        if (buildType == BuildType.GRADLE) {
            // Gradle plugins repo is also a constituent, so just use project prefix
            includedRepositories.add(projectPrefix + "-" + GRADLE_PLUGINS_REPO);
        }
        return this;
    }

    /**
     * Adds extra remote repositories to the build group that are requested for the particular build. For a Maven build
     * these are repositories defined in the root pom removed by PME by the adjust process.
     *
     * @param repositoryUrls the list of repositories to be added
     *
     */
    public ArtifactoryBuildGroupBuilder addExtraConstituents(List<String> repositoryUrls) {
        if (repositoryUrls != null && !repositoryUrls.isEmpty()) {
            List<String> splittedRepos = new ArrayList<>();
            for (String repoToSplit : repositoryUrls) {
                if (!StringUtils.isEmpty(repoToSplit)) {
                    if (repoToSplit.contains("\\n")) {
                        for (String repoUrl : repoToSplit.split("\\\\n")) {
                            splittedRepos.add(repoUrl.trim());
                        }
                    } else {
                        splittedRepos.add(repoToSplit.trim());
                    }
                }
            }

            if (!splittedRepos.isEmpty()) {
                Set<ArtifactRepository> repositories = splittedRepos.stream()
                        .map(this::createArtifactRepository)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                for (ArtifactRepository artifactRepository : repositories) {
                    if (!artifactory.repository(artifactRepository.id).exists()) {
                        logger.info(
                                "Creating remote repository {} from url {}",
                                artifactRepository.id,
                                artifactRepository.url);
                        if (!artifactory.repository(artifactRepository.id).exists()) {
                            RemoteRepository r = artifactory.repositories()
                                    .builders()
                                    .remoteRepositoryBuilder()
                                    .projectKey(configuration.getArtifactoryProject())
                                    .listRemoteFolderItems(false)
                                    .environments(Collections.singletonList(configuration.getEnvironment()))
                                    .archiveBrowsingEnabled(true)
                                    .description("Remote repository for " + artifactRepository.url)
                                    .repositorySettings(settings)
                                    .url(artifactRepository.url)
                                    .key(artifactRepository.id)
                                    .build();

                            artifactory.repositories().create(REPO_UI_POSITION, r);
                        }
                    }
                    includedRepositories.add(artifactRepository.id);
                }
            }
        }
        return this;
    }

    private ArtifactRepository createArtifactRepository(String url) {
        ArtifactRepository result = null;
        URI uri = null;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.warn("Malformed repository URL entered: {}. Skipping!", url);
        }
        if (uri != null) {
            String host = uri.getHost();
            if (host == null) {
                logger.warn("No host in repository URL entered: {}. Skipping!", url);
            } else {
                // Generate a human-readable, length-safe repository key.
                // Format: {host-slug}-{12-char-md5-of-full-url}
                // Max length: 41 chars — within both the 58-char remote and 64-char local limits.
                // The full original URL is stored in the repository description for human lookup.
                String id = ArtifactoryUtils.generateRepoIdFromUrl(host, url);

                result = ArtifactRepository.builder().id(id).name(id).url(url).releases(true).snapshots(false).build();
            }
        }

        return result;
    }

    public VirtualRepository build() {
        return artifactory.repositories()
                .builders()
                .virtualRepositoryBuilder()
                .artifactoryRequestsCanRetrieveRemoteArtifacts(true)
                .projectKey(configuration.getArtifactoryProject())
                .environments(Collections.singletonList(configuration.getEnvironment()))
                .defaultDeploymentRepo(deploymentRepository)
                .repositorySettings(settings)
                .description(description)
                .repositories(includedRepositories)
                .key(name)
                .build();
    }

    @Getter
    @Builder(builderClassName = "Builder")
    public static class ArtifactRepository {

        String id;

        String name;

        String url;

        Boolean releases;

        Boolean snapshots;

    }
}
