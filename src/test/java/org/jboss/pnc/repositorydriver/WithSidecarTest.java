package org.jboss.pnc.repositorydriver;

import static io.restassured.RestAssured.given;
import static org.jboss.pnc.repositorydriver.DriverTest.requestHeaders;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.repositorydriver.profile.WithSidecar;
import org.jboss.pnc.repositorydriver.runtime.BifrostLogUploaderProducer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;

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
        Mockito.doNothing().when(bifrostLogUploader).uploadString(any(), any());
        BifrostLogUploaderProducer bifrostLogUploaderProducer = Mockito.mock(BifrostLogUploaderProducer.class);
        Mockito.when(bifrostLogUploaderProducer.createClient(any(), anyInt(), anyInt())).thenReturn(bifrostLogUploader);
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
