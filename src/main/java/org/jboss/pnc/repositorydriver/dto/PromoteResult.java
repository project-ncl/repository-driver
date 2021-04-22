package org.jboss.pnc.repositorydriver.dto;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.jboss.pnc.repositorydriver.constants.CompletionStatus;

@Getter
@AllArgsConstructor
@Jacksonized
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromoteResult {

    private final List<org.jboss.pnc.dto.Artifact> builtArtifacts;
    private final List<org.jboss.pnc.dto.Artifact> dependencies;
    private final String buildContentId;
    private final String message;
    private final CompletionStatus completionStatus;

    public static PromoteResult failed(String buildContentId, String message, CompletionStatus status) {
        return new PromoteResult(Collections.emptyList(), Collections.emptyList(), buildContentId, message, status);
    }
}
