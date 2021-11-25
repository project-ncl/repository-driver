package org.jboss.pnc.repositorydriver.profile;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.Map;

public class WithSidecar implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Collections.singletonMap("repository-driver.indy-sidecar.enabled", "true");
    }
}
