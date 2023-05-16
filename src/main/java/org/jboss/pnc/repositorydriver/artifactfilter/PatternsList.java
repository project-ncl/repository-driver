package org.jboss.pnc.repositorydriver.artifactfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class PatternsList {

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

    /**
     * Checks if the given string matches one of the patterns in this list.
     *
     * @param string the string
     * @return true if there is a matching pattern, false otherwise
     */
    public boolean matchesOne(String string) {
        if (patterns != null) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(string).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

}
