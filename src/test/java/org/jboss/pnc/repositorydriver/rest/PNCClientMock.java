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
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MAVEN", "3.6.3");
        attributes.put("NPM", "8.19.2");
        attributes.put("GENERIC", "1.0");

        Environment environment = Environment.builder()
                .attributes(attributes)
                .build();

        // Return a mock Build object with required fields
        return Build.builder()
                .id(id)
                .startTime(Instant.now())
                .environment(environment)
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