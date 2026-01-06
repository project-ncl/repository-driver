package org.jboss.pnc.repositorydriver;

public enum DeploymentType {
    PROD("pnc"), STAGE("pnc-stage"), DEVEL("pnc-devel");

    private final String value;

    DeploymentType(String s) {
        value = s;
    }

    @Override
    public String toString() {
        return value;
    }
}
