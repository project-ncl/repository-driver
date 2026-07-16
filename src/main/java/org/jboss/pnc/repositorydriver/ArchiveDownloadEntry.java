package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@ToString
public class ArchiveDownloadEntry {
    private final RepositoryId repositoryId;
    private final String path;
    private final String md5;
    private final String sha256;
    private final String sha1;
    private final Long size;

    public static ArchiveDownloadEntry fromTrackedEntry(
            TrackedEntry entry,
            TargetRepository targetRepository) {
        // targetRepository.getRepositoryPath() is an Artifactory URL path, e.g.:
        //   /artifactory/{project}-{name}
        //   /artifactory/api/npm/{project}-{name}
        // Strip the known prefixes to recover the bare "{project}-{name}" segment.
        String repositoryPath = targetRepository.getRepositoryPath();
        if (repositoryPath.startsWith("/artifactory/api/npm/")) {
            repositoryPath = repositoryPath.substring("/artifactory/api/npm/".length());
        } else if (repositoryPath.startsWith("/artifactory/")) {
            repositoryPath = repositoryPath.substring("/artifactory/".length());
        }

        int firstHyphen = repositoryPath.indexOf('-');
        if (firstHyphen <= 0) {
            throw new IllegalArgumentException(
                    "Invalid repository path format: " + targetRepository.getRepositoryPath()
                            + ". Expected format: /artifactory/{project}-{name}");
        }

        String project = repositoryPath.substring(0, firstHyphen);
        String name = repositoryPath.substring(firstHyphen + 1);

        RepositoryId newId = RepositoryId.builder()
                .project(project)
                .name(name)
                .packageType(entry.getRepoId().getPackageType())
                .build();
        return new ArchiveDownloadEntry(
                newId,
                entry.getPath(),
                entry.getMd5(),
                entry.getSha256(),
                entry.getSha1(),
                entry.getSize());
    }
}
