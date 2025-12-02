package org.jboss.pnc.repositorydriver;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Builder
@Getter
@ToString
public class ArchivePayload {
    private String buildConfigId;
    private List<ArchiveDownloadEntry> downloads;
}
