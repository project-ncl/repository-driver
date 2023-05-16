package org.jboss.pnc.repositorydriver.artifactfilter;

import lombok.ToString;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ToString
public class IgnoredPatterns {

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
        if (patternsList != null) {
            return patternsList.getPatterns().stream().map(Pattern::pattern).collect(Collectors.toList());
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
