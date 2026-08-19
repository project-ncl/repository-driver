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
package org.jboss.pnc.repositorydriver.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RepositoryDriverException}, specifically verifying that the root cause message
 * is surfaced by {@link RepositoryDriverException#getMessage()} when the exception is constructed
 * with a {@link Throwable} cause.
 *
 * <p>
 * A call site like {@code throw new RepositoryDriverException("Failed to retrieve tracking report for: %s.", e,
 * buildContentId)} passes no placeholder for the cause, so without the appending fix the cause's message
 * would be silently lost.
 */
public class RepositoryDriverExceptionTest {

    @Test
    void getMessage_withoutCause_returnsMessage() {
        RepositoryDriverException ex = new RepositoryDriverException("simple message");
        assertEquals("simple message", ex.getMessage());
    }

    @Test
    void getMessage_withCause_includesCauseMessage() {
        // Reproduce the pattern used in ArtifactoryProducer and retrieveTrackingReport:
        //   throw new RepositoryDriverException("Fatal error contacting artifactory", e);
        // where 'e' carries a detail message that callers need in uploadLogs.
        String causeDetail = "Connection refused: artifactory.example.com:8081";
        IOException cause = new IOException(causeDetail);

        String wrapperMessage = "Fatal error contacting artifactory";
        RepositoryDriverException ex = new RepositoryDriverException(wrapperMessage, cause);

        // Cause must be correctly chained
        assertNotNull(ex.getCause(), "Cause should be set");
        assertEquals(causeDetail, ex.getCause().getMessage(), "Cause message should be the IOException body");

        // getMessage() must now include the cause so callers receive full detail
        String exMessage = ex.getMessage();
        assertTrue(exMessage.contains(wrapperMessage), "getMessage() must contain the wrapper message");
        assertTrue(exMessage.contains(causeDetail), "getMessage() must contain the cause detail");
        assertEquals(wrapperMessage + ": " + causeDetail, exMessage);
    }

    @Test
    void getMessage_withCause_andFormatParam_includesCauseMessage() {
        // Reproduce the pattern used in retrieveTrackingReport after simplification:
        //   throw new RepositoryDriverException("Failed to retrieve tracking report for: %s.", e, buildContentId);
        // The cause detail must still appear even though a format param is present.
        String causeDetail = "HTTP 503: Service Unavailable";
        IOException cause = new IOException(causeDetail);

        RepositoryDriverException ex = new RepositoryDriverException(
                "Failed to retrieve tracking report for: %s.",
                cause,
                "build-12345");

        String exMessage = ex.getMessage();
        assertTrue(exMessage.contains("build-12345"), "getMessage() must contain the interpolated build id");
        assertTrue(exMessage.contains(causeDetail), "getMessage() must contain the cause detail");
        assertEquals("Failed to retrieve tracking report for: build-12345.: " + causeDetail, exMessage);
    }

    @Test
    void getMessage_withCause_noDuplication_whenCauseAlreadyInMessage() {
        // If the format string already contains the cause message (e.g. via %s), it must not be appended again.
        IOException cause = new IOException("root cause detail");
        RepositoryDriverException ex = new RepositoryDriverException(
                "Failure: %s",
                cause,
                "root cause detail");

        String msg = ex.getMessage();
        assertEquals(
                1,
                countOccurrences(msg, "root cause detail"),
                "cause message must not be duplicated when already interpolated via format string");
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
