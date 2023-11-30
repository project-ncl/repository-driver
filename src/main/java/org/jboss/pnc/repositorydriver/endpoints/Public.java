/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.pnc.repositorydriver.endpoints;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.jboss.pnc.api.repositorydriver.dto.ArchiveRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCollectRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.repositorydriver.Driver;
import org.jboss.pnc.repositorydriver.RepositoryDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class Public {

    private static final Logger logger = LoggerFactory.getLogger(Public.class);

    @Inject
    Driver driver;

    /**
     * Create a new repository for the build. If Indy responds with en error an error response is returned to the
     * invoker.
     */
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    @POST
    @Path("/create")
    public RepositoryCreateResponse create(RepositoryCreateRequest repositoryCreateRequest)
            throws RepositoryDriverException {
        logger.info("Requested new repository: {}", repositoryCreateRequest.getBuildContentId());
        return driver.create(repositoryCreateRequest);
    }

    /**
     * Seals the tracking report.
     *
     * @param buildContentId
     */
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    @PUT
    @Path("/seal")
    public void seal(String buildContentId) throws RepositoryDriverException {
        logger.info("Sealing: {}", buildContentId);
        driver.sealTrackingReport(buildContentId);
    }

    /**
     * Retrieves the tracking report from Indy and promotes the repository. The endpoint returns after tracking report
     * retrieval, if the retrieval fails and error response is returned. The promotion is an async operation, the result
     * is sent via callback defined in the {@link RepositoryPromoteRequest}
     */
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    @PUT
    @Path("/promote")
    public void promote(RepositoryPromoteRequest promoteRequest) throws RepositoryDriverException {
        logger.info("Requested promotion: {}", promoteRequest.getBuildContentId());
        driver.promote(promoteRequest);
    }

    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    @POST
    @Path("/archive")
    public void archive(ArchiveRequest archiveRequest) throws RepositoryDriverException {
        logger.info("Requested archival: {}", archiveRequest.getBuildContentId());
        driver.archive(archiveRequest);
    }

    /**
     * Gets repository manager result for a specific buildContentId. It generates a successful result from tracking
     * report even for builds that failed because of a system error with a sealed tracking record.
     */
    @GET
    @Path("/{id}/repository-manager-result")
    public RepositoryPromoteResult collectRepoManagerResult(
            @PathParam("id") String buildContentId,
            RepositoryCollectRequest collectRequest) throws RepositoryDriverException {
        logger.info("Getting repository manager result for build record id {}.", buildContentId);
        return driver.collectRepoManagerResult(
                buildContentId,
                collectRequest.isTempBuild(),
                collectRequest.getBuildCategory());
    }

}
