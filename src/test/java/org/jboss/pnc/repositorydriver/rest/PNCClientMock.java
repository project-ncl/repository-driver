package org.jboss.pnc.repositorydriver.rest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.BuildConfigurationRevision;
import org.jboss.pnc.dto.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.test.Mock;

/**
 * Mock implementation of PNCClient for tests.
 * Returns mock Build objects instead of calling real PNC orchestrator.
 */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
@RestClient
public class PNCClientMock implements PNCClient {

    private static final Logger logger = LoggerFactory.getLogger(PNCClientMock.class);

    @Override
    public Build getSpecific(String id) {
        logger.info("Mock: Getting build for id: {}", id);

        // Create mock environment with attributes
        Map<String, String> envAttributes = new HashMap<>();
        envAttributes.put("MAVEN", "3.6.3");
        envAttributes.put("NPM", "8.19.2");
        envAttributes.put("GENERIC", "1.0");

        Environment environment = Environment.builder()
                .attributes(envAttributes)
                .build();

        // Create mock build attributes - only include BREW_BUILD_NAME for specific test IDs
        Map<String, String> buildAttributes = new HashMap<>();
        if ("test-id".equals(id)) {
            // Only provide BREW_BUILD_NAME for "test-id" to test primary extraction path
            buildAttributes.put("BREW_BUILD_NAME", "com.example:test-artifact");
            buildAttributes.put("BREW_BUILD_VERSION", "1.0.0");
        } else if ("X".equals(id)) {
            // Provide BREW_BUILD_NAME for "X" (used by DriverTest and ArtifactoryDriverTest)
            buildAttributes.put("BREW_BUILD_NAME", "org.example:build-x-artifact");
            buildAttributes.put("BREW_BUILD_VERSION", "1.0.0");
        }
        // For other IDs (like "build-without-brew-name"), don't include BREW_BUILD_NAME
        // to test exception throwing when no module name can be determined

        // Return a mock Build object with required fields
        return Build.builder()
                .id(id)
                .startTime(Instant.now())
                .environment(environment)
                .attributes(buildAttributes)
                .buildConfigRevision(
                        BuildConfigurationRevision.builder()
                                .id("1")
                                .rev(1)
                                .name("mock-config")
                                .buildScript("mvn clean install")
                                .scmRevision("abc123")
                                .build())
                .build();
    }
}

// Made with Bob