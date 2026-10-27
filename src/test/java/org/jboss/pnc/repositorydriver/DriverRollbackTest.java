package org.jboss.pnc.repositorydriver;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.enums.ResultStatus;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryRollbackRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryRollbackResult;
import org.jboss.pnc.bifrost.upload.BifrostLogUploader;
import org.jboss.pnc.repositorydriver.runtime.ArtifactoryProducer;
import org.jboss.pnc.repositorydriver.runtime.BifrostLogUploaderProducer;
import org.jboss.pnc.repositorydriver.testresource.WiremockTestServer;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.BuildSearches;
import org.jfrog.artifactory.client.Builds;
import org.jfrog.artifactory.client.Searches;
import org.jfrog.artifactory.client.model.AqlBuild;
import org.jfrog.artifactory.client.model.PncRollbackRequest;
import org.jfrog.artifactory.client.model.impl.PncRollbackResponseImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Tests the {@code /rollback} endpoint against a mocked Artifactory.
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
@QuarkusTestResource(WiremockTestServer.class)
public class DriverRollbackTest {

    private static final String BUILD_CONTENT_ID = "build-R";
    private static final String BUILD_NAME = "com.example:test-artifact";

    private static final Builds builds = Mockito.mock(Builds.class);
    private static final BuildSearches buildSearches = Mockito.mock(BuildSearches.class);

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    public static void beforeClass() throws Exception {
        BifrostLogUploader bifrostLogUploader = Mockito.mock(BifrostLogUploader.class);
        BifrostLogUploaderProducer bifrostLogUploaderProducer = Mockito.mock(BifrostLogUploaderProducer.class);
        when(bifrostLogUploaderProducer.createClient(any(), anyInt(), anyInt())).thenReturn(bifrostLogUploader);
        QuarkusMock.installMockForType(bifrostLogUploaderProducer, BifrostLogUploaderProducer.class);

        Artifactory artifactory = Mockito.mock(Artifactory.class);
        Searches searches = Mockito.mock(Searches.class);
        when(artifactory.searches()).thenReturn(searches);
        when(searches.builds()).thenReturn(buildSearches);
        when(artifactory.builds()).thenReturn(builds);

        ArtifactoryProducer artifactoryProducer = Mockito.mock(ArtifactoryProducer.class);
        when(artifactoryProducer.produceAdmin()).thenReturn(artifactory);
        when(artifactoryProducer.produceGenericClient()).thenReturn(artifactory);
        when(artifactoryProducer.producePackageClient()).thenReturn(artifactory);
        QuarkusMock.installMockForType(artifactoryProducer, ArtifactoryProducer.class);
    }

    @BeforeEach
    public void resetMocks() throws IOException {
        // reset (not just clearInvocations) so stubs throwing in a previous test do not leak into the next one
        Mockito.reset(builds, buildSearches);
        doReturn(buildSearches).when(buildSearches).project(anyString());
        doReturn(buildSearches).when(buildSearches).number(anyString());
        doReturn(List.of()).when(buildSearches).doSearch();
        PncRollbackResponseImpl response = new PncRollbackResponseImpl();
        response.setMessage("Build rolled back");
        doReturn(response).when(builds).rollbackPNCBuild(anyString(), anyString(), any(), anyString());
    }

    @Test
    public void shouldRollbackPrimaryAndGenericBuildsAndSkipDependencies() throws Exception {
        // given
        when(buildSearches.doSearch()).thenReturn(
                List.of(
                        build(
                                BUILD_NAME,
                                promotion("promoted", "pnc-mvn-old", "2026-01-01T00:00:00.000Z"),
                                promotion("promoted", "pnc-mvn-builds", "2026-01-02T00:00:00.000Z")),
                        build(
                                BUILD_NAME + ":dependencies",
                                promotion("promoted", "pnc-mvn-imports", "2026-01-02T00:00:00.000Z")),
                        build(
                                BUILD_NAME + ":generic",
                                promotion("promoted", "pnc-gen-downloads", "2026-01-02T00:00:00.000Z"))));

        // when
        RepositoryRollbackResult result = rollback();

        // then
        Assertions.assertEquals(ResultStatus.SUCCESS, result.getStatus());
        Assertions.assertEquals(BUILD_CONTENT_ID, result.getBuildContentId());

        ArgumentCaptor<PncRollbackRequest> primaryRequest = ArgumentCaptor.forClass(PncRollbackRequest.class);
        verify(builds).rollbackPNCBuild(eq(BUILD_NAME), eq(BUILD_CONTENT_ID), primaryRequest.capture(), anyString());
        Assertions.assertEquals("pnc-mvn-builds", primaryRequest.getValue().getTargetRepository());
        Assertions.assertTrue(primaryRequest.getValue().getCopy());
        Assertions.assertTrue(primaryRequest.getValue().getArtifacts());
        Assertions.assertFalse(primaryRequest.getValue().getDependencies());

        ArgumentCaptor<PncRollbackRequest> genericRequest = ArgumentCaptor.forClass(PncRollbackRequest.class);
        verify(builds).rollbackPNCBuild(
                eq(BUILD_NAME + ":generic"),
                eq(BUILD_CONTENT_ID),
                genericRequest.capture(),
                anyString());
        Assertions.assertEquals("pnc-gen-downloads", genericRequest.getValue().getTargetRepository());
        Assertions.assertFalse(genericRequest.getValue().getCopy());

        verify(builds, never()).rollbackPNCBuild(eq(BUILD_NAME + ":dependencies"), anyString(), any(), anyString());
    }

    @Test
    public void shouldSucceedWhenNoBuildInfosFound() throws Exception {
        // when
        RepositoryRollbackResult result = rollback();

        // then
        Assertions.assertEquals(ResultStatus.SUCCESS, result.getStatus());
        verify(builds, never()).rollbackPNCBuild(anyString(), anyString(), any(), anyString());
    }

    @Test
    public void shouldSucceedWhenNoPromotedPromotion() throws Exception {
        // given
        when(buildSearches.doSearch()).thenReturn(
                List.of(build(BUILD_NAME, promotion("rolledback", "pnc-mvn-builds", "2026-01-01T00:00:00.000Z"))));

        // when
        RepositoryRollbackResult result = rollback();

        // then
        Assertions.assertEquals(ResultStatus.SUCCESS, result.getStatus());
        verify(builds, never()).rollbackPNCBuild(anyString(), anyString(), any(), anyString());
    }

    @Test
    public void shouldFailFastWhenRollbackFails() throws Exception {
        // given
        when(buildSearches.doSearch()).thenReturn(
                List.of(
                        build(BUILD_NAME, promotion("promoted", "pnc-mvn-builds", "2026-01-01T00:00:00.000Z")),
                        build(
                                BUILD_NAME + ":generic",
                                promotion("promoted", "pnc-gen-downloads", "2026-01-01T00:00:00.000Z"))));
        when(builds.rollbackPNCBuild(anyString(), anyString(), any(), anyString())).thenThrow(new IOException("boom"));

        // when
        RepositoryRollbackResult result = rollback();

        // then
        Assertions.assertEquals(ResultStatus.FAILED, result.getStatus());
        verify(builds, times(1)).rollbackPNCBuild(anyString(), anyString(), any(), anyString());
    }

    @Test
    public void shouldReturnSystemErrorWhenSearchFails() {
        // given
        when(buildSearches.doSearch()).thenThrow(new RuntimeException("search failed"));

        // when
        RepositoryRollbackResult result = rollback();

        // then
        Assertions.assertEquals(ResultStatus.SYSTEM_ERROR, result.getStatus());
    }

    @Test
    @TestSecurity(user = "user", roles = "pnc-app-repository-driver-rollback")
    public void shouldAllowRollbackRole() {
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(DriverTest.requestHeaders())
                .body(request())
                .when()
                .delete("/rollback")
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "user", roles = "some-other-role")
    public void shouldForbidOtherRoles() {
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(DriverTest.requestHeaders())
                .body(request())
                .when()
                .delete("/rollback")
                .then()
                .statusCode(403);
    }

    private static RepositoryRollbackRequest request() {
        return RepositoryRollbackRequest.builder().buildContentId(BUILD_CONTENT_ID).build();
    }

    private RepositoryRollbackResult rollback() {
        return given().contentType(MediaType.APPLICATION_JSON)
                .headers(DriverTest.requestHeaders())
                .body(request())
                .when()
                .delete("/rollback")
                .then()
                .statusCode(200)
                .extract()
                .as(RepositoryRollbackResult.class);
    }

    private AqlBuild build(String name, String... promotions) throws JsonProcessingException {
        String json = String.format(
                "{\"name\":\"%s\",\"number\":\"%s\",\"promotions\":[%s]}",
                name,
                BUILD_CONTENT_ID,
                String.join(",", promotions));
        return mapper.readValue(json, AqlBuild.class);
    }

    private static String promotion(String status, String repo, String created) {
        return String.format("{\"status\":\"%s\",\"repo\":\"%s\",\"created\":\"%s\"}", status, repo, created);
    }
}
