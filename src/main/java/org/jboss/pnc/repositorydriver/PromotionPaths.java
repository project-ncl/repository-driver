package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class PromotionPaths {

    private Set<SourceTargetPaths> sourceTargetsPaths = new HashSet<>();

    public void add(RepositoryKey source, RepositoryKey target, String path) {
        SourceTargetPaths sourceTargetPaths = getSourceTarget(source, target);
        sourceTargetPaths.addPath(path);
    }

    private synchronized SourceTargetPaths getSourceTarget(RepositoryKey source, RepositoryKey target) {
        Optional<SourceTargetPaths> sourceTargetPaths = sourceTargetsPaths.stream()
                .filter(e -> e.getSource().equals(source) && e.getTarget().equals(target))
                .findAny();
        if (sourceTargetPaths.isPresent()) {
            return sourceTargetPaths.get();
        } else {
            SourceTargetPaths newSTP = new SourceTargetPaths(source, target);
            sourceTargetsPaths.add(newSTP);
            return newSTP;
        }
    }

    public Set<SourceTargetPaths> getSourceTargetsPaths() {
        return sourceTargetsPaths;
    }
}

// Made with Bob
