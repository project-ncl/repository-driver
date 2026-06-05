package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.api.trackingservice.dto.PackageType;
import org.jboss.pnc.api.trackingservice.dto.TrackedEntry;

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
    private final PackageType packageType;
    private final String path;
    private final String md5;
    private final String sha256;
    private final String sha1;
    private final Long size;

    public static ArchiveDownloadEntry fromTrackedEntry(
            TrackedEntry entry,
            TargetRepository targetRepository) {
        // targetRepository.getRepositoryPath() contains both project and name in format: {project}-{name}
        // We need to split it to extract the project and name parts
        String repositoryPath = targetRepository.getRepositoryPath();
        
        int firstHyphen = repositoryPath.indexOf('-');
        if (firstHyphen <= 0) {
            throw new IllegalArgumentException(
                    "Invalid repository path format: " + repositoryPath + ". Expected format: {project}-{name}");
        }
        
        String project = repositoryPath.substring(0, firstHyphen);
        String name = repositoryPath.substring(firstHyphen + 1);
        
        RepositoryId newId = RepositoryId.builder()
                .project(project)
                .name(name)
                .build();
        return new ArchiveDownloadEntry(
                newId,
                entry.getPackageType(),
                entry.getPath(),
                entry.getMd5(),
                entry.getSha256(),
                entry.getSha1(),
                entry.getSize());
    }
}
