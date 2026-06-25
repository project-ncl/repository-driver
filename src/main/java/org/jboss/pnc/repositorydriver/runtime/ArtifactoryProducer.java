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
            @ConfigProperty(name = "repository-driver.artifactory-client.access-token") String accessToken)
            throws RepositoryDriverException {
        try {
            // TODO: ### Remove the token from the log. Only outputting the last few characters to enable differentiation
            logger.info("Creating artifactory connection with url {} and token {}", url, accessToken.substring(accessToken.length() - 8));
            artifactory = ArtifactoryClientBuilder.create()
                    .setAccessToken(accessToken)
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
