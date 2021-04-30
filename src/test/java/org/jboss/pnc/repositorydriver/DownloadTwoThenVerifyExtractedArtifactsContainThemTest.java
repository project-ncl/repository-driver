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
package org.jboss.pnc.repositorydriver;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.jboss.pnc.repositorydriver.constants.IndyRepositoryConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class DownloadTwoThenVerifyExtractedArtifactsContainThemTest {

    @Inject
    TrackingReportProcessor trackingReportProcessor;

    @Test
    public void extractBuildArtifacts_ContainsTwoDownloads() throws Exception {

        //given
        TrackedContentDTO report = new TrackedContentDTO();
        Set<TrackedContentEntryDTO> downloads = new HashSet<>();

        StoreKey centralKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.remote, "central");
        StoreKey sharedImportsKey = new StoreKey(PackageTypeConstants.PKG_TYPE_MAVEN, StoreType.hosted, IndyRepositoryConstants.SHARED_IMPORTS_ID);

        String pomPath = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.pom";
        String jarPath = "/org/commonjava/indy/indy-core/0.17.0/indy-core-0.17.0.jar";
        TrackedContentEntryDTO downloadPom = new TrackedContentEntryDTO(
                centralKey,
                AccessChannel.NATIVE,
                pomPath
        );
        downloads.add(downloadPom);
        TrackedContentEntryDTO downloadJar = new TrackedContentEntryDTO(
                centralKey,
                AccessChannel.NATIVE,
                jarPath
        );
        downloads.add(downloadJar);
        report.setDownloads(downloads);

        //when
        PromotionPaths promotionPaths = trackingReportProcessor.collectDownloadsPromotions(report);
        Set<SourceTargetPaths> sourceTargetPaths = promotionPaths.getSourceTargetPaths();

        //then
        Assertions.assertEquals(1, sourceTargetPaths.size());

        SourceTargetPaths fromCentralToSharedImports = sourceTargetPaths.stream().findAny().get();
        Assertions.assertEquals(centralKey, fromCentralToSharedImports.getSource());
        Assertions.assertEquals(sharedImportsKey, fromCentralToSharedImports.getTarget());

        Set<String> paths = fromCentralToSharedImports.getPaths();
        Set<String> expected = new HashSet<>();
        expected.add(pomPath);
        expected.add(pomPath + "sha1");
        expected.add(pomPath + "md5");
        expected.add(jarPath);
        expected.add(jarPath + "sha1");
        expected.add(jarPath + "md5");

        Assertions.assertLinesMatch(expected.stream(), paths.stream());

    }


}
