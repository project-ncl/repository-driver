package org.jboss.pnc.repositorydriver.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.repositorydriver.Configuration;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ArtifactoryProducer {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryProducer.class);

    private final Artifactory artifactory;

    public ArtifactoryProducer(
            @ConfigProperty(name = "repository-driver.backend") Configuration.Backend backend,
            @ConfigProperty(name = "repository-driver.artifactory-client.url") String url,
            @ConfigProperty(name = "repository-driver.artifactory-client.accessToken") String accessToken) {
        if (backend == Configuration.Backend.ARTIFACTORY) {
            logger.info("### Creating artifactory with url {}", url);
            artifactory = ArtifactoryClientBuilder.create()
                    // TODO: #### DRAFT FIXME - Do we use a httpprocessor or an accesstoken API. Do we need a username?
                    .setAccessToken(accessToken)
                    .setUrl(url)
                    .build();
            //                .setUsername("pnc").build();
            //                .setHttpProcessor(
            //                        new HttpHeaderProcessor(accessToken))
            //                .setPassword("password")
        } else {
            artifactory = null;
        }
    }

    @Produces
    public Artifactory produce() {
        return artifactory;
    }
}