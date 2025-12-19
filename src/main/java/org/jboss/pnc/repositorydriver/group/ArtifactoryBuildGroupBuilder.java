package org.jboss.pnc.repositorydriver.group;

import static org.jboss.pnc.repositorydriver.Driver.GRADLE_PLUGINS_REPO;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.COMMON_BUILD_GROUP_CONSTITUENTS_GROUP;
import static org.jboss.pnc.repositorydriver.constants.RepositoryConstants.TEMPORARY_BUILDS_GROUP;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
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
    private String keyName;
    private String description;
    private List<String> includedRepositories = new ArrayList<>();

    // use #builder
    private ArtifactoryBuildGroupBuilder() {
    }

    public static ArtifactoryBuildGroupBuilder builder(
            Configuration configuration,
            Artifactory artifactory,
            RepositorySettings packageType,
            String virtualName) {

        ArtifactoryBuildGroupBuilder buildGroupBuilder = new ArtifactoryBuildGroupBuilder();
        buildGroupBuilder.artifactory = artifactory;
        buildGroupBuilder.settings = packageType;
        buildGroupBuilder.configuration = configuration;
        buildGroupBuilder.keyName = virtualName;
        return buildGroupBuilder;
    }

    public ArtifactoryBuildGroupBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public ArtifactoryBuildGroupBuilder addConstituent(String storeKey) {
        includedRepositories.add(storeKey);
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
    public ArtifactoryBuildGroupBuilder addGlobalConstituents(BuildType buildType, boolean tempBuild) {
        // 1. global builds artifacts
        if (tempBuild) {
            includedRepositories.add(
                    ArtifactoryUtils.createRepositoryName(configuration, buildType, false, tempBuild, TEMPORARY_BUILDS_GROUP));
        }
        includedRepositories.add(
                ArtifactoryUtils
                        .createRepositoryName(configuration, buildType, false, tempBuild, COMMON_BUILD_GROUP_CONSTITUENTS_GROUP));

        // add build-type-specific constituents
        switch (buildType) {
            case GRADLE:
                // TODO: ### Is this the only place the gradle plugin repo is handled?
                includedRepositories.add(
                        ArtifactoryUtils.createRepositoryName(configuration, buildType, false, tempBuild, GRADLE_PLUGINS_REPO));
                break;

            default:
                // no build-type-specific constituents for others
                break;
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
                        // TODO: What are Indy 'implied' repositories - why can't we just use a single remote repository?
                        if (!artifactory.repository(artifactRepository.id).exists()) {
                            RemoteRepository r = artifactory.repositories()
                                    .builders()
                                    .remoteRepositoryBuilder()
                                    .archiveBrowsingEnabled(true)
                                    .description("Remote repository for " + artifactRepository.url)
                                    .repositorySettings(settings)
                                    .url(artifactRepository.url)
                                    // TODO: Do we need to use convertIllegalCharacters(id) like for Indy
                                    .key(artifactRepository.id)
                                    .build();

                            artifactory.repositories().create(1, r);
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
                String id = host.replaceAll("\\.", "-");
                result = ArtifactRepository.builder().id(id).name(id).url(url).releases(true).snapshots(false).build();
            }
        }

        return result;
    }

    public VirtualRepository build() {

        return artifactory.repositories()
                .builders()
                .virtualRepositoryBuilder()
                .repositorySettings(settings)
                .description(description)
                .repositories(includedRepositories)
                .key(keyName)
                .build();
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
