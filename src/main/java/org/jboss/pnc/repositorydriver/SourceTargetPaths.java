package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.Set;

import org.commonjava.indy.model.core.StoreKey;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class SourceTargetPaths {

    private final StoreKey source;
    private final StoreKey target;
    private final Set<String> paths;

    public SourceTargetPaths(StoreKey source, StoreKey target) {
        this.source = source;
        this.target = target;
        this.paths = new HashSet<>();
    }

    public StoreKey getSource() {
        return source;
    }

    public StoreKey getTarget() {
        return target;
    }

    public Set<String> getPaths() {
        return paths;
    }

    public void addPath(String path) {
        paths.add(path);
    }
}
