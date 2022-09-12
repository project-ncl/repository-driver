package org.jboss.pnc.repositorydriver;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import lombok.extern.slf4j.Slf4j;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

@ApplicationScoped
@Slf4j
public class ApplicationLifecycle {
    void onStart(@Observes StartupEvent ev) {
        // NCL-7315: we need to log startup and shutdown
        log.info("The application is starting");
    }

    void onStop(@Observes ShutdownEvent ev) {
        // NCL-7315: we need to log startup and shutdown
        log.info("The application is stopping");
    }
}
