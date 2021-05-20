package org.jboss.pnc.repositorydriver;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class FailedResponseException extends RuntimeException {

    public FailedResponseException(String message) {
        super(message);
    }
}
