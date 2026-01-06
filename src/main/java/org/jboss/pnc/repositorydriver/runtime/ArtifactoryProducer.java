package org.jboss.pnc.repositorydriver.runtime;

import jakarta.annotation.PreDestroy;
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
                    // Attempting to use setPassword fails because we are not defining a username. setAccessToken
                    // also doesn't appear to work but defining a HTTPProcessor to add the appropriate header works.
                    // .setAccessToken(accessToken)
                    // .setPassword(accessToken)
                    // .setUsername("pnc").build();
                    .setHttpProcessor(new ArtifactoryTokenProcessor(accessToken))
                    .setUrl(url)
                    .build();

            try {
                // TODO: To remove? Useful for debugging to ensure we have the right URL configured.
                logger.info(
                        "Running against Artifactory version {}",
                        artifactory.system().version().getVersion());
            } catch (Exception e) {
                // TODO: Should we migrate all exceptions to RepositoryDriverException ?
                throw new RuntimeException("Fatal error contacting artifactory", e);
            }
        } else {
            artifactory = null;
        }
    }

    @Produces
    public Artifactory produce() {
        return artifactory;
    }

    @PreDestroy
    public void cleanup() {
        logger.warn("Closing artifactory connection");
        artifactory.close();
    }
}
