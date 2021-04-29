package org.jboss.pnc.repositorydriver.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.jboss.pnc.enums.BuildType;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Getter
@AllArgsConstructor
@Jacksonized
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateRequest {

    String buildContentId;
    BuildType buildType;
    boolean tempBuild;
    List<ArtifactRepository> extraRepositories;

}
