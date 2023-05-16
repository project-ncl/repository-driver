/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2020 Red Hat, Inc., and individual contributors
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
package org.jboss.pnc.repositorydriver.artifactfilter;

import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;

public interface ArtifactFilter {

    /**
     * Checks if the artifact should be accepted or not.
     *
     * @param artifact the audited artifact
     * @return true if the artifact should be accepted, false otherwise
     */
    boolean accepts(TrackedContentEntryDTO artifact);

}
