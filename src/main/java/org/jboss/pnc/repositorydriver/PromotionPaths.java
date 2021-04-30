package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.Set;

import org.commonjava.indy.model.core.StoreKey;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class PromotionPaths {

    private Set<SourceTargetPaths> sourceTargetPaths = new HashSet<>();

    //TODO delete me
//    private Map<StoreKey, Map<StoreKey, Set<String>>> sourceTargetPathsMap = new HashMap<>();
//    public void addToMap(StoreKey source, StoreKey target, String path) {
//        Map<StoreKey, Set<String>> sources = sourceTargetPathsMap.computeIfAbsent(target, t -> new HashMap<>());
//        Set<String> paths = sources.computeIfAbsent(source, s -> new HashSet<>());
//        paths.add(path);
//    }

    public void add(StoreKey source, StoreKey target, String path) {
        SourceTargetPaths sourceTargetPaths = getSourceTarget(source, target);
        sourceTargetPaths.addPath(path);
    }

    private SourceTargetPaths getSourceTarget(StoreKey source, StoreKey target) {
        return sourceTargetPaths.stream()
                .filter(e -> e.getSource().equals(source) && e.getTarget().equals(target))
                .findAny()
                .orElse(new SourceTargetPaths(source, target));
    }

    public Set<SourceTargetPaths> getSourceTargetPaths() {
        return sourceTargetPaths;
    }
}
