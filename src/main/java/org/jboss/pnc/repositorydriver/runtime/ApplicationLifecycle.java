/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.pnc.repositorydriver.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@ApplicationScoped
public class ApplicationLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationLifecycle.class);

    private AtomicInteger activePromotions = new AtomicInteger();
    private boolean shuttingDown;

    void onStart(@Observes StartupEvent event) {
    }

    void onStop(@Observes ShutdownEvent event) {
        shuttingDown = true;
        Duration shutdownTimeout = ConfigProvider.getConfig().getValue("quarkus.shutdown.timeout", Duration.class);
        Instant shutdownStarted = Instant.now();
        while (activePromotions.get() > 0) {
            if (Duration.between(shutdownStarted, Instant.now()).compareTo(shutdownTimeout) > 0) {
                logger.warn("Reached quarkus.shutdown.timeout: {}", shutdownTimeout.toString());
                break;
            }
            try {
                logger.info("Waiting for {} promotions to complete ...", activePromotions.get());
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for promotions to complete.", e);
                break;
            }
        }
    }

    public void addActivePromotion() {
        activePromotions.incrementAndGet();
    }

    public void removeActivePromotion() {
        activePromotions.decrementAndGet();
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
