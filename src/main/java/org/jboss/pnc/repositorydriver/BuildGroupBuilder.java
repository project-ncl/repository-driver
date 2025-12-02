package org.jboss.pnc.repositorydriver;

import static org.jboss.pnc.repositorydriver.Driver.GRADLE_PLUGINS_REPO;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.COMMON_BUILD_GROUP_CONSTITUENTS_GROUP;
import static org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants.TEMPORARY_BUILDS_GROUP;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.commonjava.indy.client.core.Indy;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.StoreListingDTO;
import org.jboss.pnc.api.enums.BuildType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Builder;
import lombok.Getter;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class BuildGroupBuilder {

    private static final Logger userLog = LoggerFactory.getLogger("org.jboss.pnc._userlog_.repository-driver");

    private Indy indy;
    private Group buildGroup;
    private String packageType;
    private String buildContentId;

    // use #builder
    private BuildGroupBuilder() {
    }

    public static BuildGroupBuilder builder(Indy indy, String packageType, String buildContentId) {
        BuildGroupBuilder buildGroupBuilder = new BuildGroupBuilder();
        buildGroupBuilder.indy = indy;
        buildGroupBuilder.packageType = packageType;
        buildGroupBuilder.buildContentId = buildContentId;
        buildGroupBuilder.buildGroup = new Group(packageType, buildContentId);
        return buildGroupBuilder;
    }

    public BuildGroupBuilder withDescription(String description) {
        buildGroup.setDescription(description);
        return this;
    }

    public BuildGroupBuilder addConstituent(StoreKey storeKey) {
        buildGroup.addConstituent(storeKey);
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
    public BuildGroupBuilder addGlobalConstituents(BuildType buildType, boolean tempBuild) {
        // 1. global builds artifacts
        if (tempBuild) {
            buildGroup.addConstituent(new StoreKey(packageType, StoreType.hosted, TEMPORARY_BUILDS_GROUP));
        }
        buildGroup.addConstituent(new StoreKey(packageType, StoreType.group, COMMON_BUILD_GROUP_CONSTITUENTS_GROUP));

        // add build-type-specific constituents
        switch (buildType) {
            case GRADLE:
                buildGroup.addConstituent(StoreKey.fromString(GRADLE_PLUGINS_REPO));
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
     * @throws IndyClientException in case of an issue when communicating with the repository manager
     */
    public BuildGroupBuilder addExtraConstituents(List<String> repositoryUrls) throws IndyClientException {
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
                                        + repository.getId() + ") from repository declaration removed by PME (repo: "
                                        + buildContentId + ")");
                        indy.stores()
                                .create(
                                        remoteRepo,
                                        "Creating extra remote repository " + repository.getName() + " ("
                                                + repository.getId() + ") repo: " + buildContentId,
                                        RemoteRepository.class);
                    }

                    buildGroup.addConstituent(remoteKey);
                }
            }
        }
        return this;
    }

    /**
     * Add metadata to the build group
     *
     * @param key key of metadata
     * @param value value of metadata
     * @return
     */
    public BuildGroupBuilder addMetadata(String key, String value) {
        buildGroup.setMetadata(key, value);
        return this;
    }

    private ArtifactRepository createArtifactRepository(String url) {
        ArtifactRepository result = null;
        URI uri = null;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            userLog.warn("Malformed repository URL entered: {}. Skipping!", url);
        }

        if (uri != null) {
            String host = uri.getHost();
            if (host == null) {
                userLog.warn("No host in repository URL entered: {}. Skipping!", url);
            } else {
                String id = host.replaceAll("\\.", "-");
                result = ArtifactRepository.builder().id(id).name(id).url(url).releases(true).snapshots(false).build();
            }
        }

        return result;
    }

    public Group build() {
        return buildGroup;
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
