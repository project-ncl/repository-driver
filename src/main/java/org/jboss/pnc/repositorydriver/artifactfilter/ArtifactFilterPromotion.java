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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.pnc.api.tracker.dto.PackageType;
import org.jboss.pnc.api.tracker.dto.TrackedEntry;
import org.jboss.pnc.repositorydriver.Configuration;

/**
 * Default implementation of artifact filter allowing filtering artifacts by path patterns and also filtering downloads
 * for promotion by store key patterns.
 *
 * @author pkocandr
 */
@ApplicationScoped
public class ArtifactFilterPromotion implements ArtifactFilter {

    @Inject
    Configuration configuration;

    private IgnoredPatterns ignoredPathPatternsPromotion;

    @PostConstruct
    public void init() {
        ignoredPathPatternsPromotion = new IgnoredPatterns();
        configuration.getIgnoredPathPatternsPromotionGeneric()
                .ifPresent(p -> ignoredPathPatternsPromotion.setGeneric(p));
        configuration.getIgnoredPathPatternsPromotionMaven().ifPresent(p -> ignoredPathPatternsPromotion.setMaven(p));
        configuration.getIgnoredPathPatternsPromotionNpm().ifPresent(p -> ignoredPathPatternsPromotion.setNpm(p));
    }

    @Override
    public boolean accepts(TrackedEntry artifact) {
        String path = artifact.getPath();
        PackageType packageType = artifact.getPackageType();
        return !ignoreContent(ignoredPathPatternsPromotion, packageType, path);
    }

    private boolean ignoreContent(IgnoredPatterns ignoredPathPatterns, PackageType packageType, String path) {
        PatternsList patterns;
        switch (packageType) {
            case MVN:
                patterns = ignoredPathPatterns.getMaven();
                break;
            case NPM:
                patterns = ignoredPathPatterns.getNpm();
                break;
            case GENERIC:
                patterns = ignoredPathPatterns.getGeneric();
                break;
            default:
                throw new IllegalArgumentException(
                        "Package type " + packageType + " is not supported by " + getClass().getSimpleName());
        }

        return patterns.matchesOne(path);
    }

}
