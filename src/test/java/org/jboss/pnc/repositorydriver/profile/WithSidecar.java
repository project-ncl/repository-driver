package org.jboss.pnc.repositorydriver.profile;

import java.util.Collections;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class WithSidecar implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Collections.singletonMap("repository-driver.indy-sidecar.enabled", "true");
    }
}
