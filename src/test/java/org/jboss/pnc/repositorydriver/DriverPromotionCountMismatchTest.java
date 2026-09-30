package org.jboss.pnc.repositorydriver;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.constants.HttpHeaders;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.api.enums.BuildCategory;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.repositorydriver.invokerserver.CallbackHandler;
import org.jboss.pnc.repositorydriver.invokerserver.HttpServer;
import org.jboss.pnc.repositorydriver.invokerserver.ServletInstanceFactory;
import org.jboss.pnc.repositorydriver.runtime.ArtifactoryProducer;
import org.jboss.pnc.repositorydriver.runtime.BifrostLogUploaderProducer;
import org.jboss.pnc.repositorydriver.testresource.WiremockTestServer;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.Builds;
import org.jfrog.artifactory.client.ItemHandle;
import org.jfrog.artifactory.client.Repositories;
import org.jfrog.artifactory.client.RepositoryHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Verifies that a mismatch between the expected promotion count and the count reported by the
 * Artifactory plugin causes the build promotion to be reported as {@link ResultStatus#FAILED}.
 *
 * <p>
 * Kept in a separate class to avoid shared-mock lifecycle issues with {@link DriverTest}.
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@QuarkusTestResource(WiremockTestServer.class)
public class DriverPromotionCountMismatchTest {

    private static final String BIND_HOST = "127.0.0.1";

    private static final Logger logger = LoggerFactory.getLogger(DriverPromotionCountMismatchTest.class);

    @Inject
    ObjectMapper mapper;

    private static HttpServer callbackServer;

    private static final BlockingQueue<Request> callbackRequests = new ArrayBlockingQueue<>(100);

    @BeforeAll
    public static void beforeClass() throws Exception {
        callbackServer = new HttpServer();
        callbackServer.addServlet(
                CallbackHandler.class,
                new ServletInstanceFactory(new CallbackHandler(callbackRequests::add)));
        callbackServer.start(8083, BIND_HOST);

        BifrostLogUploader bifrostLogUploader = Mockito.mock(BifrostLogUploader.class);
        Mockito.doNothing().when(bifrostLogUploader).uploadString(any(), any());
        BifrostLogUploaderProducer bifrostLogUploaderProducer = Mockito.mock(BifrostLogUploaderProducer.class);
        Mockito.when(bifrostLogUploaderProducer.createClient(any(), anyInt(), anyInt())).thenReturn(bifrostLogUploader);
        QuarkusMock.installMockForType(bifrostLogUploaderProducer, BifrostLogUploaderProducer.class);

        Artifactory artifactory = Mockito.mock(Artifactory.class);
        RepositoryHandle repositoryHandle = Mockito.mock(RepositoryHandle.class);
        Mockito.when(artifactory.repository(Mockito.anyString())).thenReturn(repositoryHandle);
        ItemHandle itemHandle = Mockito.mock(ItemHandle.class);
        Mockito.when(repositoryHandle.folder(Mockito.anyString())).thenReturn(itemHandle);
        Mockito.when(repositoryHandle.exists()).thenReturn(true);
        Mockito.when(artifactory.repositories()).thenReturn(Mockito.mock(Repositories.class, RETURNS_DEEP_STUBS));

        // The mock tracking report has 2 Maven downloads (pom + jar), both promotable dependencies.
        // Return 99 deps — intentional mismatch vs the 2 expected — to trigger the count-validation check.
        Builds builds = Mockito.mock(Builds.class, RETURNS_DEEP_STUBS);
        org.jfrog.artifactory.client.model.impl.PncPromotionResponseImpl promotionResponse = new org.jfrog.artifactory.client.model.impl.PncPromotionResponseImpl();
        promotionResponse.setMessage("Build successfully promoted");
        promotionResponse.setPromotedArts(5);
        promotionResponse.setPromotedDeps(99);
        Mockito.when(
                builds.promotePNCBuild(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString()))
                .thenReturn(promotionResponse);
        Mockito.when(artifactory.builds()).thenReturn(builds);

        ArtifactoryProducer artifactoryProducer = Mockito.mock(ArtifactoryProducer.class);
        Mockito.when(artifactoryProducer.produceAdmin()).thenReturn(artifactory);
        Mockito.when(artifactoryProducer.produceGenericClient()).thenReturn(artifactory);
        Mockito.when(artifactoryProducer.producePackageClient()).thenReturn(artifactory);
        QuarkusMock.installMockForType(artifactoryProducer, ArtifactoryProducer.class);
    }

    @AfterAll
    public static void afterClass() {
        callbackServer.stop();
    }

    @Test
    @Timeout(15)
    public void shouldFailPromotionOnCountMismatch() throws URISyntaxException, InterruptedException {
        // given
        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8083/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(
                        new Request.Header(HttpHeaders.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));
        RepositoryPromoteRequest request = RepositoryPromoteRequest.builder()
                .buildContentId("build-Y")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .buildCategory(BuildCategory.STANDARD)
                .callback(callbackRequest)
                .rtBuildStartTime(java.time.Instant.now())
                .rtBuildName("com.example:test-artifact")
                .rtBuildVersion("1.0.0")
                .rtEnvironmentTools(java.util.Map.of("MAVEN", "3.6.3"))
                .build();

        // when
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(DriverTest.requestHeaders())
                .body(request)
                .when()
                .put("/promote")
                .then()
                .statusCode(204);

        // then — the plugin returned 99 deps but only 2 were expected → PromotionValidationException → FAILED
        Request callback = callbackRequests.take();
        RepositoryPromoteResult promoteResult = mapper
                .convertValue(callback.getAttachment(), RepositoryPromoteResult.class);
        logger.info("Promotion completed with status: {}", promoteResult.getStatus());
        Assertions.assertEquals(ResultStatus.FAILED, promoteResult.getStatus());
    }
}
