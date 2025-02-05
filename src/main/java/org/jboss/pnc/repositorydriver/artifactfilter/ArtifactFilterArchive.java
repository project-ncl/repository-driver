/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.repositorydriver.artifactfilter;

import jakarta.annotation.PostConstruct;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.StoreKey;
import org.jboss.pnc.repositorydriver.Configuration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collections;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

/**
 * Default implementation of artifact filter allowing filtering artifacts by path patterns and also filtering downloads
 * for promotion by store key patterns.
 *
 * @author pkocandr
 */
@ApplicationScoped
public class ArtifactFilterArchive implements ArtifactFilter {

    @Inject
    Configuration configuration;

    private IgnoredPatterns ignoredPathPatterns;

    private PatternsList ignoredRepoPatterns;

    @PostConstruct
    public void init() {
        ignoredPathPatterns = new IgnoredPatterns();
        configuration.getIgnoredPathPatternsArchiveMaven().ifPresent(p -> ignoredPathPatterns.setMaven(p));

        ignoredRepoPatterns = configuration.getIgnoredRepoPatternsArchive()
                .map(PatternsList::new)
                .orElse(new PatternsList(Collections.emptyList()));
    }

    @Override
    public boolean accepts(TrackedContentEntryDTO artifact) {
        String path = artifact.getPath();
        StoreKey storeKey = artifact.getStoreKey();
        return !ignoreContent(ignoredPathPatterns, storeKey.getPackageType(), path)
                && !ignoredRepoPatterns.matchesOne(storeKey.toString());
    }

    private boolean ignoreContent(IgnoredPatterns ignoredPathPatterns, String packageType, String path) {
        switch (packageType) {
            case MAVEN_PKG_KEY:
                return ignoredPathPatterns.getMaven().matchesOne(path);
            case NPM_PKG_KEY:
                return true;
            case GENERIC_PKG_KEY:
                return true;
            default:
                throw new IllegalArgumentException(
                        "Package type " + packageType + " is not supported by " + getClass().getSimpleName());
        }
    }

}
