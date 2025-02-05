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
package org.jboss.pnc.repositorydriver.runtime;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.jboss.pnc.api.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mapper that catches all exception and extracts the http status code from the JAXRS/RESTEASY runtime exception. Status
 * code extraction is required for proper response error codes eg. 404.
 *
 * @author Honza Brázdil &lt;jbrazdil@redhat.com&gt;
 * @author Matej Lazar &lt;mlazar@redhat.com&gt;
 */
@Provider
public class DefaultExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultExceptionMapper.class);

    @Override
    public Response toResponse(Throwable e) {
        int status = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        Response response = null;

        if (e instanceof WebApplicationException) {
            response = ((WebApplicationException) e).getResponse();
            if (e instanceof NotFoundException) {
                return response; // In case of 404 we want to return the empty body.
            }
            logger.debug("A WebApplicationExceptio occurred when processing REST response", e);
        } else {
            logger.error("An exception occurred when processing REST response", e);
        }

        Response.ResponseBuilder builder;

        if (response != null) {
            builder = Response.status(response.getStatus());

            // copy headers
            for (Map.Entry<String, List<Object>> en : response.getMetadata().entrySet()) {
                String headerName = en.getKey();
                List<Object> headerValues = en.getValue();
                for (Object headerValue : headerValues) {
                    builder.header(headerName, headerValue);
                }
            }
        } else {
            builder = Response.status(status);
        }

        builder.entity(new ErrorResponse(e)).type(MediaType.APPLICATION_JSON);

        return builder.build();
    }
}
