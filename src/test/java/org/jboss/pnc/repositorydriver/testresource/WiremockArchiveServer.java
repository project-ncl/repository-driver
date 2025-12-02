package org.jboss.pnc.repositorydriver.testresource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import java.util.Collections;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WiremockArchiveServer implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wiremock;

    @Override
    public Map<String, String> start() {
        wiremock = new WireMockServer();
        wiremock.start();

        stubFor(post(urlEqualTo("/archive")).willReturn(aResponse().withStatus(204)));
        return Collections.singletonMap("repository-driver.archive-service.api-url", wiremock.baseUrl() + "/archive");
    }

    @Override
    public void stop() {
        wiremock.stop();
    }
}
