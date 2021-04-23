package org.jboss.pnc.repositorydriver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.jboss.pnc.api.dto.Request;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Getter
@AllArgsConstructor
@Jacksonized
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateResponse {

    private final String repositoryDependencyUrl;

    private final String repositoryDeployUrl;

    private final String repositoryBuildContentId; // TODO do we need this ?

}
