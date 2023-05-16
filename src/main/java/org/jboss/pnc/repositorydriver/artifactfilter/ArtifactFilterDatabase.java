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

import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.StoreKey;
import org.jboss.pnc.repositorydriver.Configuration;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

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
public class ArtifactFilterDatabase implements ArtifactFilter {

    @Inject
    Configuration configuration;

    private IgnoredPatterns ignoredPathPatternsData;

    @PostConstruct
    public void init() {
        ignoredPathPatternsData = new IgnoredPatterns();
        configuration.getIgnoredPathPatternsResultGeneric().ifPresent(p -> ignoredPathPatternsData.setGeneric(p));
        configuration.getIgnoredPathPatternsResultMaven().ifPresent(p -> ignoredPathPatternsData.setMaven(p));
        configuration.getIgnoredPathPatternsResultNpm().ifPresent(p -> ignoredPathPatternsData.setNpm(p));
    }

    @Override
    public boolean accepts(TrackedContentEntryDTO artifact) {
        boolean result = true;

        String path = artifact.getPath();
        StoreKey storeKey = artifact.getStoreKey();
        if (ignoreContent(ignoredPathPatternsData, storeKey.getPackageType(), path)) {
            result = false;
        }
        return result;
    }

    private boolean ignoreContent(IgnoredPatterns ignoredPathPatterns, String packageType, String path) {
        PatternsList patterns;
        switch (packageType) {
            case MAVEN_PKG_KEY:
                patterns = ignoredPathPatterns.getMaven();
                break;
            case NPM_PKG_KEY:
                patterns = ignoredPathPatterns.getNpm();
                break;
            case GENERIC_PKG_KEY:
                patterns = ignoredPathPatterns.getGeneric();
                break;
            default:
                throw new IllegalArgumentException(
                        "Package type " + packageType + " is not supported by " + getClass().getSimpleName());
        }

        return patterns.matchesOne(path);
    }

}
