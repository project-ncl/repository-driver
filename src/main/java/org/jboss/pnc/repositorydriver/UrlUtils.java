package org.jboss.pnc.repositorydriver;

import java.net.MalformedURLException;
import java.net.URL;

public class UrlUtils {
    /**
     * Substitute the url with a new host (with new scheme and port), leaving the rest unchanged
     *
     * @param url Url to change host (e.g http://example.com/booya)
     * @param newHost the new host (e.g https://localhost:1234)
     *
     * @return modified url
     *
     * @throws MalformedURLException If urls are malformed
     */
    public static String replaceHostInUrl(String url, String newHost) throws MalformedURLException {
        if (url == null || newHost == null) {
            return url;
        }

        URL originalUrl = new URL(url);
        URL newHostUrl = new URL(newHost);
        int newPort = newHostUrl.getPort();

        // Use implicit port if it's a default port
        boolean useDefaultPort = newPort == newHostUrl.getDefaultPort();

        newPort = useDefaultPort ? -1 : newPort;
        URL newURL = new URL(newHostUrl.getProtocol(), newHostUrl.getHost(), newPort, originalUrl.getFile());
        return newURL.toString();
    }
}
