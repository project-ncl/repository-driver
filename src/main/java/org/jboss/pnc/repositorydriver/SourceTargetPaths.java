package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class SourceTargetPaths {

    private final RepositoryKey source;
    private final RepositoryKey target;
    private final Set<String> paths;

    public SourceTargetPaths(RepositoryKey source, RepositoryKey target) {
        this.source = source;
        this.target = target;
        this.paths = new HashSet<>();
    }

    public RepositoryKey getSource() {
        return source;
    }

    public RepositoryKey getTarget() {
        return target;
    }

    public Set<String> getPaths() {
        return paths;
    }

    public void addPath(String path) {
        paths.add(path);
    }
}
