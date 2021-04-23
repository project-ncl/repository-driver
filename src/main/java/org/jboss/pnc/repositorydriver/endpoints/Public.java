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

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import io.quarkus.security.Authenticated;
import org.jboss.pnc.repositorydriver.Driver;
import org.jboss.pnc.repositorydriver.RepositoryDriverException;
import org.jboss.pnc.repositorydriver.dto.CreateRequest;
import org.jboss.pnc.repositorydriver.dto.CreateResponse;
import org.jboss.pnc.repositorydriver.dto.PromoteRequest;
import org.jboss.pnc.repositorydriver.dto.PromoteResult;
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
     * Create a new repository session tuned to the parameters of that build collection and the build that will use this
     * repository session.
     */
    @Authenticated
    @POST
    @Path("/create")
    public CreateResponse create(CreateRequest createRequest) throws RepositoryDriverException {
        logger.info("Requested new repository: {}", createRequest.getBuildContentId());
        return driver.create(createRequest);
    }

    @Authenticated
    @PUT
    @Path("/promote")
    public void promote(PromoteRequest promoteRequest) throws RepositoryDriverException {
        logger.info("Requested promotion: {}", promoteRequest.getBuildContentId());
        driver.promote(promoteRequest);
    }

    /**
     * Gets repository manager result for a specific Build Record. It generates a successful result from tracking report
     * even for builds that failed because of a system error with a sealed tracking record.
     */
    @GET
    @Path("/{id}/repository-manager-result")
    public PromoteResult collectRepoManagerResult(
            @PathParam("id") String buildRecordId,
            @QueryParam("temp") boolean tempBuild) throws RepositoryDriverException {
        logger.info("Getting repository manager result for build record id {}.", buildRecordId);
        return driver.collectRepoManagerResult(buildRecordId, tempBuild);
    }

}
