package org.jboss.pnc.repositorydriver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@AllArgsConstructor
@Builder
@Getter
@ToString
public class ArchivePayload {
    private String buildConfigId;
    private List<ArchiveDownloadEntry> downloads;
}
