package org.jboss.pnc.repositorydriver.testresource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class WiremockTestServer implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wiremock;

    @Override
    public Map<String, String> start() {
        wiremock = new WireMockServer();
        wiremock.start();

        stubFor(post(urlEqualTo("/archive")).willReturn(aResponse().withStatus(204)));

        wiremock.stubFor(post(urlEqualTo("/heartbeat")).willReturn(aResponse().withStatus(200)));

        return Map.of(
                "test.wiremock.url", wiremock.baseUrl(),
                "repository-driver.archive-service.api-url", wiremock.baseUrl() + "/archive"
        );
    }

    @Override
    public void stop() {
        wiremock.stop();
    }
}
