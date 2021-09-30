package org.jboss.pnc.repositorydriver.testresource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Collections;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class WiremockArchiveServer implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wiremock;

    @Override
    public Map<String, String> start() {
        wiremock = new WireMockServer();
        wiremock.start();

        stubFor(post(urlEqualTo("/archival")).willReturn(aResponse().withStatus(204)));
        return Collections.singletonMap("repository-driver.archive-service.api-url", wiremock.baseUrl() + "/archival");
    }

    @Override
    public void stop() {
        wiremock.stop();
    }
}
