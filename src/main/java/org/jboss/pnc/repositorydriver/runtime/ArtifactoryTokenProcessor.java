package org.jboss.pnc.repositorydriver.runtime;

    import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.*;

import java.io.IOException;

public class ArtifactoryTokenProcessor implements HttpProcessor {
    private final String apiKey;

    public ArtifactoryTokenProcessor(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void process(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
        httpRequest.addHeader("X-JFrog-Art-Api", apiKey);
    }

    @Override
    public void process(HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
    }
}

