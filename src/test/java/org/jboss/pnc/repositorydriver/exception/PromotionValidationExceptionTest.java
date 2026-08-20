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
 * Tests for {@link PromotionValidationException}, specifically verifying that the root cause message
 * is surfaced by {@link PromotionValidationException#getMessage()} when the exception is constructed
 * with a {@link Throwable} cause.
 *
 * <p>
 * Previously, when {@code promoteToRepository} caught an {@link IOException} and wrapped it in
 * {@code new PromotionValidationException(message, e)}, the cause's message was silently lost.
 * {@code promote} called {@code uploadLogs(wrapperMessage + e.getMessage(), "promote")} where
 * {@code e.getMessage()} returned only the wrapper message — the Artifactory HTTP 400 body
 * never reached bifrost.
 */
public class PromotionValidationExceptionTest {

    @Test
    void getMessage_withoutCause_returnsMessage() {
        PromotionValidationException ex = new PromotionValidationException("simple message");
        assertEquals("simple message", ex.getMessage());
    }

    @Test
    void getMessage_withCause_includesCauseMessage() {
        // Reproduce the exact construction used in Driver.promoteToRepository:
        //   throw new PromotionValidationException(message, e);
        // where 'e' is an IOException whose message contains the Artifactory 400 body.
        String artifactory400Body = "status code: 400, reason phrase: {\"messages\":[{\"level\":\"ERROR\","
                + "\"message\":\"Re-promotion blocked: artifact already exists\"}]}";
        IOException cause = new IOException(artifactory400Body);

        String wrapperMessage = "Failed to promote artifacts for BuildInfo foo #bar to repository pnc-devel-mvn-ibm-builds";
        PromotionValidationException ex = new PromotionValidationException(wrapperMessage, cause);

        // Cause must be correctly chained
        assertNotNull(ex.getCause(), "Cause should be set");
        assertEquals(artifactory400Body, ex.getCause().getMessage(), "Cause message should be the IOException body");

        // getMessage() must now include the cause so uploadLogs receives the full detail
        String exMessage = ex.getMessage();
        assertTrue(
                exMessage.contains(wrapperMessage),
                "getMessage() must contain the wrapper message");
        assertTrue(
                exMessage.contains(artifactory400Body),
                "getMessage() must contain the Artifactory 400 body from the cause");
        assertEquals(wrapperMessage + ": " + artifactory400Body, exMessage);
    }

    @Test
    void getMessage_withCause_uploadLogsContent_containsCauseMessage() {
        // Simulate what Driver.promote assembles for uploadLogs (line 454):
        //   uploadLogs(message + e.getMessage(), "promote")
        // where 'message' is "Failed promoting downloaded or uploaded artifacts: "
        // and 'e' is the PromotionValidationException
        IOException cause = new IOException("HTTP 400: Re-promotion blocked");
        PromotionValidationException pve = new PromotionValidationException(
                "Failed to promote artifacts for BuildInfo X #Y to repository Z",
                cause);

        String uploadLogsMessage = "Failed promoting downloaded or uploaded artifacts: " + pve.getMessage();

        // The string sent to bifrost must now contain the root HTTP error
        assertTrue(
                uploadLogsMessage.contains("HTTP 400"),
                "uploadLogs content must contain the root cause HTTP error");
        assertTrue(
                uploadLogsMessage.contains("Re-promotion blocked"),
                "uploadLogs content must contain the Artifactory error detail");
    }

    @Test
    void getMessage_withCause_noDuplication_whenCauseAlreadyInMessage() {
        // If the format string already contains the cause message (e.g. via %s), it should not be appended again.
        IOException cause = new IOException("root cause detail");
        PromotionValidationException ex = new PromotionValidationException(
                "Failure: %s",
                cause,
                "root cause detail");

        String msg = ex.getMessage();
        // Should appear exactly once
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
