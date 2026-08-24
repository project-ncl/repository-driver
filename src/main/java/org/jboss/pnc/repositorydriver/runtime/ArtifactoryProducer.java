package org.jboss.pnc.repositorydriver.runtime;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.repositorydriver.cdi.Admin;
import org.jboss.pnc.repositorydriver.cdi.GenericPromotion;
import org.jboss.pnc.repositorydriver.cdi.PackagePromotion;
import org.jboss.pnc.repositorydriver.exception.RepositoryDriverException;
import org.jfrog.artifactory.client.Artifactory;
import org.jfrog.artifactory.client.ArtifactoryClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ArtifactoryProducer {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactoryProducer.class);

    private final Artifactory artifactoryAdmin;
    private final Artifactory packagePromotionClient;
    private final Artifactory genericPromotionClient;

    public ArtifactoryProducer(
            @ConfigProperty(name = "repository-driver.artifactory-client.url") String url,
            @ConfigProperty(name = "repository-driver.artifactory-client.tokens.admin") String adminToken,
            @ConfigProperty(
                    name = "repository-driver.artifactory-client.tokens.generic-promotion") String packageAccessToken,
            @ConfigProperty(
                    name = "repository-driver.artifactory-client.tokens.package-promotion") String genericAccessToken,
            @ConfigProperty(
                    name = "repository-driver.artifactory-client.connect-timeout",
                    defaultValue = "5") int connectTimeout,
            @ConfigProperty(
                    name = "repository-driver.artifactory-client.request-timeout",
                    defaultValue = "180") int socketTimeout)
            throws RepositoryDriverException {
        try {

            artifactoryAdmin = createClient(url, adminToken, connectTimeout, socketTimeout, "admin");
            packagePromotionClient = createClient(
                    url,
                    packageAccessToken,
                    connectTimeout,
                    socketTimeout,
                    "package promotion");
            genericPromotionClient = createClient(
                    url,
                    genericAccessToken,
                    connectTimeout,
                    socketTimeout,
                    "generic promotion");
            logger.info(
                    "Running against Artifactory version {}",
                    artifactoryAdmin.system().version().getVersion());
        } catch (Exception e) {
            throw new RepositoryDriverException("Fatal error contacting artifactory", e);
        }
    }

    private static Artifactory createClient(
            String url,
            String accessToken,
            int connectTimeout,
            int socketTimeout,
            String alias) {
        // TODO: ### Remove the token from the log. Only outputting the last few characters to enable differentiation
        logger.info(
                "Creating artifactory {} connection with url {} and token {}",
                alias,
                url,
                accessToken.substring(accessToken.length() - 8));
        return ArtifactoryClientBuilder.create()
                .setAccessToken(accessToken)
                .setUrl(url)
                .setConnectionTimeout(connectTimeout * 1000)
                .setSocketTimeout(socketTimeout * 1000)
                .build();
    }

    @Admin
    @Produces
    public Artifactory produceAdmin() {
        return artifactoryAdmin;
    }

    @Produces
    @GenericPromotion
    public Artifactory produceGenericClient() {
        return genericPromotionClient;
    }

    @Produces
    @PackagePromotion
    public Artifactory producePackageClient() {
        return packagePromotionClient;
    }

    @PreDestroy
    public void cleanup() {
        logger.warn("Closing artifactory connections");
        artifactoryAdmin.close();
        genericPromotionClient.close();
        packagePromotionClient.close();
    }
}
