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

import java.time.ZonedDateTime;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.PathParam;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.dto.ComponentVersion;
import org.jboss.pnc.api.repositorydriver.dto.ArchiveRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCollectRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryCreateResponse;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryPromoteResult;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryRollbackRequest;
import org.jboss.pnc.api.repositorydriver.dto.RepositoryRollbackResult;
import org.jboss.pnc.api.repositorydriver.rest.DriverEndpoint;
import org.jboss.pnc.common.log.LogSanitizer;
import org.jboss.pnc.repositorydriver.Driver;
import org.jboss.pnc.repositorydriver.constants.BuildInformationConstants;
import org.jboss.pnc.repositorydriver.exception.RepositoryDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class Public implements DriverEndpoint {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    private static final Logger logger = LoggerFactory.getLogger(Public.class);

    @Inject
    Driver driver;

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    public RepositoryCreateResponse create(RepositoryCreateRequest repositoryCreateRequest)
            throws RepositoryDriverException {
        logger.info("Requested new repository: {}", LogSanitizer.clean(repositoryCreateRequest.getBuildContentId()));
        return driver.create(repositoryCreateRequest);
    }

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    public void seal(String buildContentId) {
        logger.info("Sealing: {}", LogSanitizer.clean(buildContentId));
        driver.sealTrackingReport(buildContentId);
    }

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    public void promote(RepositoryPromoteRequest promoteRequest) throws RepositoryDriverException {
        logger.info("Requested promotion: {}", LogSanitizer.clean(promoteRequest.getBuildContentId()));
        driver.promote(promoteRequest);
    }

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-rollback", "pnc-app-repository-driver-user", "pnc-users-admin" })
    public RepositoryRollbackResult rollback(RepositoryRollbackRequest rollbackRequest) {
        logger.info("Requested rollback: {}", LogSanitizer.clean(rollbackRequest.getBuildContentId()));
        return driver.rollback(rollbackRequest);
    }

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    public void archive(ArchiveRequest archiveRequest) throws RepositoryDriverException {
        logger.info("Requested archival: {}", LogSanitizer.clean(archiveRequest.getBuildContentId()));
        driver.archive(archiveRequest);
    }

    @Override
    @RolesAllowed({ "pnc-app-repository-driver-user", "pnc-users-admin" })
    public RepositoryPromoteResult collectRepoManagerResult(
            @PathParam("id") String buildContentId,
            RepositoryCollectRequest collectRequest) throws RepositoryDriverException {
        logger.info("Getting repository manager result for build record id {}.", LogSanitizer.clean(buildContentId));
        return driver.collectRepoManagerResult(
                buildContentId,
                collectRequest.isTempBuild(),
                collectRequest.getBuildCategory());
    }

    @Override
    public ComponentVersion getVersion() {
        return ComponentVersion.builder()
                .name(name)
                .version(BuildInformationConstants.VERSION)
                .commit(BuildInformationConstants.COMMIT_HASH)
                .builtOn(ZonedDateTime.parse(BuildInformationConstants.BUILD_TIME))
                .build();
    }
}
