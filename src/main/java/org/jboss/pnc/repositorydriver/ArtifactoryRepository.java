package org.jboss.pnc.repositorydriver;

import org.jboss.pnc.api.enums.BuildType;

import lombok.Data;

@Data
public class ArtifactoryRepository {

    DeploymentType deploymentType;

    BuildType buildType;

    Boolean virtualRepository;

    Boolean temporaryRepository;

    String buildId;

    public String getRepositoryName() {
        return deploymentType + "-" + buildType.getRepoType().name().toLowerCase() + "-" +
                (virtualRepository ? "virtual-" : "") + (temporaryRepository ? "temporary-" : "") + buildId;

    }
}
