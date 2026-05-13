package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;
import org.jboss.pnc.api.tracker.dto.PackageType;
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
    private final PackageType packageType;
    private final String path;
    private final String md5;
    private final String sha256;
    private final String sha1;
    private final Long size;

    public static ArchiveDownloadEntry fromTrackedEntry(
            TrackedEntry entry,
            TargetRepository targetRepository) {
        return new ArchiveDownloadEntry(
                entry.getRepoId(),
                entry.getPackageType(),
                entry.getPath(),
                entry.getMd5(),
                entry.getSha256(),
                entry.getSha1(),
                entry.getSize());
    }

    // TODO: Remove this method once Indy-specific code is fully migrated
    // /**
    //  * Splits repositoryPath like /api/content/maven/hosted/pnc-builds into a storeKey like maven:hosted:pnc-builds
    //  */
    // private static StoreKey getStoreKeyFromRepositoryPath(String repositoryPath) {
    //     String[] split = repositoryPath.split("/");
    //     if (split.length <= 2) {
    //         throw new IllegalArgumentException();
    //     }
    //
    //     return new StoreKey(split[split.length - 3], StoreType.get(split[split.length - 2]), split[split.length - 1]);
    // }
}
