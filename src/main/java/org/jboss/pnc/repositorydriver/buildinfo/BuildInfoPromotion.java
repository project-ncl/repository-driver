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
package org.jboss.pnc.repositorydriver.buildinfo;

import org.jboss.pnc.api.dto.RepositoryId;
import org.jfrog.build.api.Build;

/**
 * Wrapper containing THREE Build objects with their respective target repositories.
 *
 * <p>
 * Artifactory cannot differentiate between modules during promotion, so we use separate Build objects instead of
 * multiple modules in one Build:
 * </p>
 * <ul>
 * <li>Primary Build: Contains artifacts (uploads) and ALL filtered Maven/NPM downloads as dependencies (audit only,
 * not promoted)</li>
 * <li>Dependencies Build: Contains only the promotable Maven/NPM downloads as dependencies (promoted to imports
 * repository)</li>
 * <li>Generic Build: Contains generic downloads as artifacts (may be null)</li>
 * </ul>
 *
 * <p>
 * Each Build is uploaded separately and promoted to its respective target repository:
 * </p>
 * <ul>
 * <li>Primary Build promoted to: artifacts target (e.g., pnc-mvn-ibm-builds) — artifacts only</li>
 * <li>Dependencies Build promoted to: dependencies target (e.g., pnc-mvn-imports) — dependencies only</li>
 * <li>Generic Build promoted to: generic downloads target (e.g., pnc-gen-downloads)</li>
 * </ul>
 *
 * @param primaryBuild Build containing artifacts and all filtered downloads as deps (required)
 * @param artifactsTarget target repository for artifact promotion (may be null if no artifacts)
 * @param dependenciesBuild Build containing only promotable Maven/NPM downloads (may be null)
 * @param dependenciesTarget target repository for dependency promotion (may be null if no promotable downloads)
 * @param genericBuild Build containing generic downloads as artifacts (may be null)
 * @param genericDownloadsTarget target repository for generic downloads promotion (may be null)
 * @author <a href="mailto:ncross@redhat.com">Nick Cross</a>
 */
public record BuildInfoPromotion(
        Build primaryBuild,
        RepositoryId artifactsTarget,
        Build dependenciesBuild,
        RepositoryId dependenciesTarget,
        Build genericBuild,
        RepositoryId genericDownloadsTarget) {

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if primaryBuild is null, or if dependenciesBuild/genericBuild is provided
     *         without its corresponding target
     */
    public BuildInfoPromotion {
        if (primaryBuild == null) {
            throw new IllegalArgumentException("Primary Build cannot be null");
        }
        if (dependenciesBuild != null && dependenciesTarget == null) {
            throw new IllegalArgumentException(
                    "If dependenciesBuild is provided, dependenciesTarget must also be provided");
        }
        if (genericBuild != null && genericDownloadsTarget == null) {
            throw new IllegalArgumentException(
                    "If genericBuild is provided, genericDownloadsTarget must also be provided");
        }
    }

    /**
     * Checks if this promotion has an artifacts target repository.
     *
     * @return true if artifactsTarget is not null
     */
    public boolean hasArtifactsTarget() {
        return artifactsTarget != null;
    }

    /**
     * Checks if this promotion has a dependencies Build ready to promote.
     *
     * @return true if both dependenciesBuild and dependenciesTarget are not null
     */
    public boolean hasDependenciesBuild() {
        return dependenciesBuild != null && dependenciesTarget != null;
    }

    /**
     * Checks if this promotion has generic downloads that need promotion.
     *
     * @return true if both genericBuild and genericDownloadsTarget are not null
     */
    public boolean hasGenericDownloads() {
        return genericBuild != null && genericDownloadsTarget != null;
    }
}
