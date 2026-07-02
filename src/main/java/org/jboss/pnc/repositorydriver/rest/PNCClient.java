package org.jboss.pnc.repositorydriver.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.pnc.dto.Build;

import io.quarkus.arc.Unremovable;

@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "pnc-orch")
@Unremovable
@Path("/builds")
public interface PNCClient {
    @GET
    @Path("/{id}")
    Build getSpecific(@PathParam("id") String id);
}
