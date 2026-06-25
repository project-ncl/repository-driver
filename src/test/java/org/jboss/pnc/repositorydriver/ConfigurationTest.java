/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.repositorydriver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.jboss.pnc.api.enums.BuildCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@DisplayName("Configuration Tests")
public class ConfigurationTest {

    @Inject
    Configuration configuration;

    @Test
    @DisplayName("Should have read build category promotion properly and use default as fallback")
    void testBuildCategoryTempBuildPromotionTarget() {

        // standard doesn't define the temp build, should fall back to default from main config
        assertEquals("mvn-temp-builds", configuration.getTempBuildPromotionTarget(BuildCategory.STANDARD));
        assertEquals("mvn-builds", configuration.getBuildPromotionTarget(BuildCategory.STANDARD));

        // service does define the promotion values, should use them
        assertEquals("temporary-service-builds", configuration.getTempBuildPromotionTarget(BuildCategory.SERVICE));
        assertEquals("service-builds", configuration.getBuildPromotionTarget(BuildCategory.SERVICE));
    }

    @Test
    void testBuildGroupConstituents() {
        // defined in standard as empty list
        assertEquals(Optional.empty(), configuration.getBuildGroupConstituentsTempGroup(BuildCategory.STANDARD));

        // defined as 'temp-central' in standard
        assertEquals(
                Optional.of(List.of("temp-central")),
                configuration.getBuildGroupConstituentsTempHosted(BuildCategory.STANDARD));

        // not defined in 'standard'; should use default from main config
        assertEquals(
                Optional.of(List.of("builds-imports-public")),
                configuration.getBuildGroupConstituentsGroup(BuildCategory.STANDARD));
    }

}
