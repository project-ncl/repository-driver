package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.tracker.dto.PackageType;

import lombok.Data;

@Data
/*
 * This class may eventually not be needed but for now it encapsulates everything
 * about a repository
 */
public class RepositoryKey {
    private final RepositoryId repositoryId;
    private final PackageType packageType;
    private final boolean temporary;
}
