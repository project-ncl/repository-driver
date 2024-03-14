package org.jboss.pnc.repositorydriver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.response.ResponseBodyExtractionOptions;

import org.jboss.pnc.api.constants.HttpHeaders;
import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.api.repositorydriver.dto.ArchiveRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.repositorydriver.invokerserver.CallbackHandler;
import org.jboss.pnc.repositorydriver.invokerserver.HttpServer;
import org.jboss.pnc.repositorydriver.invokerserver.ServletInstanceFactory;
import org.jboss.pnc.repositorydriver.runtime.BifrostLogUploaderProducer;
import org.jboss.pnc.repositorydriver.testresource.WiremockArchiveServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.restassured.RestAssured.given;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@QuarkusTestResource(WiremockArchiveServer.class)
public class DriverTest {

    private static final String BIND_HOST = "127.0.0.1";

    private static final Logger logger = LoggerFactory.getLogger(DriverTest.class);

    @Inject
    ObjectMapper mapper;

    private static HttpServer callbackServer;

    private static final BlockingQueue<Request> callbackRequests = new ArrayBlockingQueue<>(100);

    @BeforeAll
    public static void beforeClass() throws Exception {
        // uncomment to log all requests
        // RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        callbackServer = new HttpServer();

        callbackServer.addServlet(
                CallbackHandler.class,
                new ServletInstanceFactory(new CallbackHandler(callbackRequests::add)));
        callbackServer.start(8082, BIND_HOST);

        BifrostLogUploader bifrostLogUploader = Mockito.mock(BifrostLogUploader.class);
        Mockito.doNothing().when(bifrostLogUploader).uploadString(Mockito.any(), Mockito.any());
        BifrostLogUploaderProducer bifrostLogUploaderProducer = Mockito.mock(BifrostLogUploaderProducer.class);
        Mockito.when(bifrostLogUploaderProducer.produce()).thenReturn(bifrostLogUploader);
        QuarkusMock.installMockForType(bifrostLogUploaderProducer, BifrostLogUploaderProducer.class);
    }

    @AfterAll
    public static void afterClass() {
        callbackServer.stop();
    }

    @Test
    public void shouldCreateRepository() {
        // given
        RepositoryCreateRequest request = RepositoryCreateRequest.builder()
                .buildContentId("build-X")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .build();
        // when
        RepositoryCreateResponse repositoryCreateResponse = given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .post("/create")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(RepositoryCreateResponse.class);

        // then
        Assertions.assertEquals(
                "http://localhost/folo/track/build-X/maven/group/build-X/",
                repositoryCreateResponse.getRepositoryDependencyUrl());
        Assertions.assertEquals(
                "http://localhost/folo/track/build-X/maven/hosted/build-X/",
                repositoryCreateResponse.getRepositoryDeployUrl());
    }

    @Test
    @Timeout(15)
    public void shouldPromoteRepository() throws URISyntaxException, InterruptedException {
        // given
        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8082/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(
                        new Request.Header(HttpHeaders.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));
        RepositoryPromoteRequest request = RepositoryPromoteRequest.builder()
                .buildContentId("build-X")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .buildCategory(BuildCategory.STANDARD)
                .callback(callbackRequest)
                .build();

        // when
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .put("/seal")
                .then()
                .statusCode(204);

        given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .put("/promote")
                .then()
                .statusCode(204);

        // then
        Request callback = callbackRequests.take();
        RepositoryPromoteResult promoteResult = mapper
                .convertValue(callback.getAttachment(), RepositoryPromoteResult.class);
        logger.info("Promotion completed with status: {}", promoteResult.getStatus());
        Assertions.assertEquals(ResultStatus.SUCCESS, promoteResult.getStatus());
    }

    @Test
    public void testArchiveRequest() {
        ResponseBodyExtractionOptions body = given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(ArchiveRequest.builder().buildConfigId("10").buildContentId("100").build())
                .when()
                .post("/archive")
                .then()
                .statusCode(204)
                .extract()
                .body();

        verify(
                1,
                postRequestedFor(urlEqualTo("/archive"))
                        .withRequestBody(matchingJsonPath("buildConfigId", containing("10"))));
    }

    public static Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MDCHeaderKeys.PROCESS_CONTEXT.getHeaderName(), "A");
        headers.put(MDCHeaderKeys.TMP.getHeaderName(), "false");
        headers.put(MDCHeaderKeys.EXP.getHeaderName(), "0");
        headers.put(MDCHeaderKeys.USER_ID.getHeaderName(), "1");
        return headers;
    }
}
