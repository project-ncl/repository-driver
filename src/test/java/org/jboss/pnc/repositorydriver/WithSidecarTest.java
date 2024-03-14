package org.jboss.pnc.repositorydriver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.repositorydriver.invokerserver.CallbackHandler;
import org.jboss.pnc.repositorydriver.invokerserver.HttpServer;
import org.jboss.pnc.repositorydriver.invokerserver.ServletInstanceFactory;
import org.jboss.pnc.repositorydriver.profile.WithSidecar;
import org.jboss.pnc.repositorydriver.runtime.BifrostLogUploaderProducer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;
import javax.ws.rs.core.MediaType;

import static io.restassured.RestAssured.given;
import static org.jboss.pnc.repositorydriver.DriverTest.requestHeaders;

@QuarkusTest
@TestProfile(WithSidecar.class)
@TestSecurity(authorizationEnabled = false)
public class WithSidecarTest {
    @Inject
    ObjectMapper mapper;

    @Inject
    Configuration configuration;

    @BeforeAll
    public static void beforeClass() throws Exception {
        BifrostLogUploader bifrostLogUploader = Mockito.mock(BifrostLogUploader.class);
        Mockito.doNothing().when(bifrostLogUploader).uploadString(Mockito.any(), Mockito.any());
        BifrostLogUploaderProducer bifrostLogUploaderProducer = Mockito.mock(BifrostLogUploaderProducer.class);
        Mockito.when(bifrostLogUploaderProducer.produce()).thenReturn(bifrostLogUploader);
        QuarkusMock.installMockForType(bifrostLogUploaderProducer, BifrostLogUploaderProducer.class);
    }

    @Test
    public void testWithSidecarEnabled() {
        // given
        RepositoryCreateRequest request = RepositoryCreateRequest.builder()
                .buildContentId("build-X")
                .buildType(BuildType.MVN)
                .tempBuild(false)
                .build();
        configuration.setSidecarEnabled(true);
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
        configuration.setSidecarEnabled(false);

        // then
        Assertions.assertEquals(
                "http://sidecar:8093/folo/track/build-X/maven/group/build-X/",
                repositoryCreateResponse.getRepositoryDependencyUrl());
        Assertions.assertEquals(
                "http://localhost/folo/track/build-X/maven/hosted/build-X/",
                repositoryCreateResponse.getRepositoryDeployUrl());
    }
}
