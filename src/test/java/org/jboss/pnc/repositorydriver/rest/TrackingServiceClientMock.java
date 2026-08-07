package org.jboss.pnc.repositorydriver.rest;

import java.util.HashSet;
import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.tracker.dto.TrackDownloadRequest;
import org.jboss.pnc.api.tracker.dto.TrackUploadRequest;
import org.jboss.pnc.api.tracker.dto.TrackingReport;
import org.jboss.pnc.repositorydriver.TrackingReportMocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.test.Mock;

/**
 * Mock implementation of TrackingServiceClient for tests.
 * Returns mock tracking reports instead of calling real tracking service.
 */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
@RestClient
public class TrackingServiceClientMock implements TrackingServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(TrackingServiceClientMock.class);

    @Override
    public void initReport(String id) {
        logger.info("Mock: Initializing tracking report for id: {}", id);
    }

    @Override
    public TrackingReport getReport(String id) {
        logger.info("Mock: Getting tracking report for id: {}", id);

        // Return a mock tracking report with some sample data
        HashSet<org.jboss.pnc.api.tracker.dto.TrackedEntry> downloads = new HashSet<>();
        downloads.add(TrackingReportMocks.indyPomFromCentral);
        downloads.add(TrackingReportMocks.indyJarFromCentral);

        HashSet<org.jboss.pnc.api.tracker.dto.TrackedEntry> uploads = new HashSet<>();
        // Add some mock uploads if needed

        return TrackingReport.builder()
                .downloads(downloads)
                .uploads(uploads)
                .build();
    }

    @Override
    public void sealReport(String id) {
        logger.info("Mock: Sealing tracking report for id: {}", id);
    }

    @Override
    public void clearReport(String id) {
        logger.info("Mock: Clearing tracking report for id: {}", id);
    }

    @Override
    public List<String> getAllIds(String pattern) {
        logger.info("Mock: Getting all tracking IDs matching pattern: {}", pattern);
        return List.of();
    }

    @Override
    public void trackDownload(String id, TrackDownloadRequest request) {
        logger.info("Mock: Tracking download for id: {}", id);
    }

    @Override
    public void trackUpload(String id, TrackUploadRequest request) {
        logger.info("Mock: Tracking upload for id: {}", id);
    }

    @Override
    public List<String> getUploadPaths(String id) {
        logger.info("Mock: Getting upload paths for id: {}", id);
        return List.of();
    }
}

// Made with Bob
