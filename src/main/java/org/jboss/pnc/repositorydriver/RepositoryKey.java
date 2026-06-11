package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jboss.pnc.api.trackingservice.dto.PackageType;

/*
 * This class may eventually not be needed but for now it encapsulates everything
 * about a repository
 */
public record RepositoryKey(RepositoryId repositoryId,
        PackageType packageType,
        boolean temporary) {
}
