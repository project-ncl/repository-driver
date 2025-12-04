package org.jboss.pnc.repositorydriver;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.repositorydriver.dto.ArchiveRequest;
import org.jboss.pnc.repositorydriver.testresource.WiremockArchiveServer;
import org.junit.jupiter.api.Test;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ResponseBodyExtractionOptions;

@QuarkusTest
@QuarkusTestResource(WiremockArchiveServer.class)
@QuarkusTestResource(
        value = LogCollectingTestResource.class,
        restrictToAnnotatedClass = true,
        initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
@TestSecurity(authorizationEnabled = false)
@TestProfile(ArchiveTest.class)
public class ArchiveTest implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "repository-driver.archive-service.enabled",
                "false");
    }

    @Test
    public void testDisabledArchiveRequest() {

        ResponseBodyExtractionOptions body = given().contentType(MediaType.APPLICATION_JSON)
                .headers(DriverTest.requestHeaders())
                .body(ArchiveRequest.builder().buildConfigId("10").buildContentId("100").build())
                .when()
                .post("/archive")
                .then()
                .statusCode(204)
                .extract()
                .body();

        verify(
                0,
                postRequestedFor(urlEqualTo("/archive"))
                        .withRequestBody(matchingJsonPath("buildConfigId", containing("10"))));

        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(
                logRecords.stream()
                        .anyMatch(
                                r -> LogCollectingTestResource.format(r)
                                        .contains(
                                                "Archive service disabled")));
    }
}
