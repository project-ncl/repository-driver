package org.jboss.pnc.repositorydriver.constants;

import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class Checksum {

    public static final Set<String> suffixes;

    static {
        suffixes = new HashSet<>(4);
        suffixes.add("md5");
        suffixes.add("sha1");
        suffixes.add("sha256");
        suffixes.add("sha512");
    }

}
