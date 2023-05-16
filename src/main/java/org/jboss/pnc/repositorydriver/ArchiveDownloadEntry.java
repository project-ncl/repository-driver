package org.jboss.pnc.repositorydriver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.jboss.pnc.api.repositorydriver.dto.TargetRepository;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@ToString
public class ArchiveDownloadEntry {
    private final StoreKey storeKey;
    private final String path;
    private final String md5;
    private final String sha256;
    private final String sha1;
    private final Long size;

    public static ArchiveDownloadEntry fromTrackedContentEntry(TrackedContentEntryDTO dto, TargetRepository targetRepository) {
        return new ArchiveDownloadEntry(
                getStoreKeyFromRepositoryPath(targetRepository.getRepositoryPath()),
                dto.getPath(),
                dto.getMd5(),
                dto.getSha256(),
                dto.getSha1(),
                dto.getSize());
    }

    /**
     * Splits repositoryPath like /api/content/maven/hosted/pnc-builds into a storeKey like maven:hosted:pnc-builds
     */
    private static StoreKey getStoreKeyFromRepositoryPath(String repositoryPath) {
        String[] split = repositoryPath.split("/");
        if (split.length <= 2) {
            throw new IllegalArgumentException();
        }

        return new StoreKey(split[split.length - 3], StoreType.get(split[split.length - 2]), split[split.length - 1]);
    }
}
