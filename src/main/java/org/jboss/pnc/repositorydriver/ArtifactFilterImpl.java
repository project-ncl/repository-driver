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
package org.jboss.pnc.repositorydriver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Setter;
import lombok.ToString;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.StoreKey;

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
public class ArtifactFilterImpl implements ArtifactFilter {

    @Inject
    Configuration configuration;

    private IgnoredPatterns ignoredPathPatternsPromotion;

    private IgnoredPatterns ignoredPathPatternsData;

    private PatternsList ignoredRepoPatterns;

    @PostConstruct
    public void init() {
        ignoredPathPatternsPromotion = new IgnoredPatterns();
        configuration.getIgnoredPathPatternsPromotionGeneric()
                .ifPresent(p -> ignoredPathPatternsPromotion.setGeneric(p));
        configuration.getIgnoredPathPatternsPromotionMaven().ifPresent(p -> ignoredPathPatternsPromotion.setMaven(p));
        configuration.getIgnoredPathPatternsPromotionNpm().ifPresent(p -> ignoredPathPatternsPromotion.setNpm(p));

        ignoredPathPatternsData = new IgnoredPatterns();
        configuration.getIgnoredPathPatternsResultGeneric().ifPresent(p -> ignoredPathPatternsData.setGeneric(p));
        configuration.getIgnoredPathPatternsResultMaven().ifPresent(p -> ignoredPathPatternsData.setMaven(p));
        configuration.getIgnoredPathPatternsResultNpm().ifPresent(p -> ignoredPathPatternsData.setNpm(p));

        if (configuration.getIgnoredRepoPatterns().isPresent()) {
            ignoredRepoPatterns = new PatternsList(configuration.getIgnoredRepoPatterns().get());
        } else {
            ignoredRepoPatterns = new PatternsList(Collections.emptyList());
        }
    }

    @Override
    public boolean acceptsForPromotion(TrackedContentEntryDTO artifact, boolean download) {
        boolean result = true;

        String path = artifact.getPath();
        StoreKey storeKey = artifact.getStoreKey();
        if (download && ignoreDependencySource(storeKey)) {
            result = false;
        } else if (ignoreContent(ignoredPathPatternsPromotion, storeKey.getPackageType(), path)) {
            result = false;
        }
        return result;
    }

    @Override
    public boolean acceptsForData(TrackedContentEntryDTO artifact) {
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
                        "Package type " + packageType + " is not supported by Indy repository manager driver.");
        }

        return matchesOne(path, patterns);
    }

    @Override
    public boolean ignoreDependencySource(StoreKey storeKey) {
        String strSK = storeKey.toString();
        return matchesOne(strSK, ignoredRepoPatterns);
    }

    /**
     * Checks if the given string matches one of the patterns.
     *
     * @param string the string
     * @param patterns the patterns list
     * @return true if there is a matching pattern, false otherwise
     */
    private boolean matchesOne(String string, PatternsList patterns) {
        if (patterns != null) {
            for (Pattern pattern : patterns.getPatterns()) {
                if (pattern.matcher(string).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static class PatternsList {

        private List<Pattern> patterns;

        public PatternsList(List<String> strings) {
            if (strings != null) {
                patterns = new ArrayList<>(strings.size());
                for (String string : strings) {
                    patterns.add(Pattern.compile(string));
                }
            }
        }

        public List<Pattern> getPatterns() {
            return patterns == null ? Collections.emptyList() : patterns;
        }
    }

    @ToString
    public static class IgnoredPatterns {

        @JsonIgnore
        private PatternsList generic;

        @JsonIgnore
        private PatternsList maven;

        @JsonIgnore
        private PatternsList npm;

        @JsonProperty("generic")
        public void setGeneric(List<String> strPatterns) {
            generic = new PatternsList(strPatterns);
        }

        @JsonProperty("maven")
        public void setMaven(List<String> strPatterns) {
            maven = new PatternsList(strPatterns);
        }

        @JsonProperty("npm")
        public void setNpm(List<String> strPatterns) {
            npm = new PatternsList(strPatterns);
        }

        @JsonIgnore
        public PatternsList getGeneric() {
            return getPatternsList(generic);
        }

        @JsonIgnore
        public PatternsList getMaven() {
            return getPatternsList(maven);
        }

        @JsonIgnore
        public PatternsList getNpm() {
            return getPatternsList(npm);
        }

        private List<String> genStringList(PatternsList patternsList) {
            if (patternsList != null && patternsList.patterns != null) {
                return patternsList.patterns.stream().map(Pattern::pattern).collect(Collectors.toList());
            } else {
                return Collections.emptyList();
            }
        }

        @JsonProperty("generic")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getGenericStrings() {
            return genStringList(generic);
        }

        @JsonProperty("maven")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getMavenStrings() {
            return genStringList(maven);
        }

        @JsonProperty("npm")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getNpmStrings() {
            return genStringList(npm);
        }

        /**
         * Safely gets patterns list. Ensures that output is never null.
         *
         * @param list the input list
         * @return an empty list in case of input list is null, otherwise the input list
         */
        @JsonIgnore
        private PatternsList getPatternsList(PatternsList list) {
            return list == null ? new PatternsList(Collections.emptyList()) : list;
        }

    }

    @ToString
    public static class IgnoredPathPatterns {
        @Setter
        private IgnoredPatterns promotion;
        @Setter
        private IgnoredPatterns data;

        public IgnoredPatterns getPromotion() {
            return promotion == null ? new IgnoredPatterns() : promotion;
        }

        public IgnoredPatterns getData() {
            return data == null ? new IgnoredPatterns() : data;
        }
    }

}
