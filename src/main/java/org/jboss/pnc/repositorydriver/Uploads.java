package org.jboss.pnc.repositorydriver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.jboss.pnc.dto.Artifact;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class Uploads {

    /**
     * List of artifacts to be stored in DB.
     */
    private List<Artifact> artifacts;

    /**
     * List of paths to be promoted.
     */
    private List<String> promotionPaths;

    Uploads(List<Artifact> artifacts, List<String> promotionPaths) {
        this.artifacts = new ArrayList<>();
        this.artifacts.addAll(artifacts);
        Collections.sort(this.artifacts, Comparator.comparing(Artifact::getIdentifier));
        this.promotionPaths = promotionPaths;
    }

    /**
     * Gets the list of uploaded artifacts to be stored in DB.
     *
     * @return the list
     */
    public List<Artifact> getArtifacts() {
        return artifacts;
    }

    /**
     * Gets the list of paths for promotion.
     *
     * @return the list
     */
    public List<String> getPromotionPaths() {
        return promotionPaths;
    }
}
