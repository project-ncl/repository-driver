package org.jboss.pnc.repositorydriver.runtime;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.repositorydriver.RepositoryDriverException;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ArtifactoryProducer {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryProducer.class);

    private final Artifactory artifactory;

    public ArtifactoryProducer(
            @ConfigProperty(name = "repository-driver.artifactory-client.url") String url,
            @ConfigProperty(name = "repository-driver.artifactory-client.accessToken") String accessToken)
            throws RepositoryDriverException {
        try {
            logger.info("Creating artifactory connection with url {} and token {}", url, accessToken);
            artifactory = ArtifactoryClientBuilder.create()
                    // Attempting to use setPassword fails because we are not defining a username.
                    // Previously setAccessToken caused issues and had to use a HTTPProcessor via
                    // ArtifactoryTokenProcessor, but currently it seems to be working
                    .setAccessToken(accessToken)
                    // .setPassword(accessToken)
                    //.setHttpProcessor(new ArtifactoryTokenProcessor(accessToken))
                    .setUrl(url)
                    .build();
            logger.info(
                    "Running against Artifactory version {}",
                    artifactory.system().version().getVersion());
        } catch (Exception e) {
            throw new RepositoryDriverException("Fatal error contacting artifactory", e);
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
