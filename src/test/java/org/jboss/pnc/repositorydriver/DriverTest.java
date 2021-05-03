package org.jboss.pnc.repositorydriver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.inject.Inject;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.jboss.pnc.api.constants.HttpHeaders;
import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.enums.BuildCategory;
import org.jboss.pnc.enums.BuildType;
import org.jboss.pnc.repositorydriver.constants.Status;
import org.jboss.pnc.repositorydriver.dto.CreateRequest;
import org.jboss.pnc.repositorydriver.dto.CreateResponse;
import org.jboss.pnc.repositorydriver.dto.PromoteRequest;
import org.jboss.pnc.repositorydriver.dto.PromoteResult;
import org.jboss.pnc.repositorydriver.invokerserver.CallbackHandler;
import org.jboss.pnc.repositorydriver.invokerserver.HttpServer;
import org.jboss.pnc.repositorydriver.invokerserver.ServletInstanceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.restassured.RestAssured.given;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
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
    }

    @AfterAll
    public static void afterClass() {
        callbackServer.stop();
    }

    @Test
    public void shouldCreateRepository() {
        //given
        CreateRequest request = CreateRequest.builder()
                .buildContentId("build-X")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .build();
        //when
        CreateResponse createResponse = given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .post("/create")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(CreateResponse.class);

        //then
        Assertions.assertEquals("http://localhost/folo/track/build-X/maven/group/build-X/", createResponse.getRepositoryDependencyUrl());
        Assertions.assertEquals("http://localhost/folo/track/build-X/maven/hosted/build-X/", createResponse.getRepositoryDeployUrl());
    }

    @Test
    @Timeout(15)
    public void shouldPromoteRepository() throws URISyntaxException, InterruptedException {
        //given
        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8082/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(
                        new Request.Header(HttpHeaders.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));
        PromoteRequest request = PromoteRequest.builder()
                .buildContentId("build-X")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .buildCategory(BuildCategory.STANDARD)
                .callback(callbackRequest)
                .build();

        //when
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .put("/promote")
                .then()
                .statusCode(204);

        //then
        Request callback = callbackRequests.take();
        PromoteResult promoteResult = mapper
                .convertValue(callback.getAttachment(), PromoteResult.class);
        logger.info("Promotion completed with status: {}", promoteResult.getStatus());
        Assertions.assertEquals(Status.SUCCESS, promoteResult.getStatus());
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MDCHeaderKeys.PROCESS_CONTEXT.getHeaderName(), "A");
        headers.put(MDCHeaderKeys.TMP.getHeaderName(), "false");
        headers.put(MDCHeaderKeys.EXP.getHeaderName(), "0");
        headers.put(MDCHeaderKeys.USER_ID.getHeaderName(), "1");
        return headers;
    }
}
