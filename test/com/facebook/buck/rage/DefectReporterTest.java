/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rage;

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.TriState;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DefectReporterTest {

  private static final BuildEnvironmentDescription TEST_ENV_DESCRIPTION =
      BuildEnvironmentDescription.builder()
          .setUser("test_user")
          .setHostname("test_hostname")
          .setOs("test_os")
          .setAvailableCores(1)
          .setSystemMemory(1024L)
          .setBuckDirty(TriState.FALSE)
          .setBuckCommit("test_commit")
          .setJavaVersion("test_java_version")
          .setJsonProtocolVersion(1)
          .build();

  private static final UserLocalConfiguration TEST_USER_LOCAL_CONFIGURATION =
      UserLocalConfiguration.of(true, ImmutableSet.of(Paths.get(".buckconfig.local")));

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testAttachesPaths() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    RageConfig config = RageConfig.builder()
        .build();
    Clock clock = new DefaultClock();
    DefectReporter reporter = new DefaultDefectReporter(
        filesystem,
        ObjectMappers.newDefaultInstance(),
        config,
        BuckEventBusFactory.newInstance(clock),
        clock);

    Path fileToBeIncluded = Paths.get("FileToBeIncluded.txt");
    filesystem.touch(fileToBeIncluded);
    String fileToBeIncludedContent = "testcontentbehere";
    filesystem.writeContentsToPath(fileToBeIncludedContent, fileToBeIncluded);

    DefectSubmitResult defectSubmitResult = reporter.submitReport(
        DefectReport.builder()
            .setBuildEnvironmentDescription(TEST_ENV_DESCRIPTION)
            .setIncludedPaths(fileToBeIncluded)
            .setUserLocalConfiguration(TEST_USER_LOCAL_CONFIGURATION)
            .build());

    Path reportPath = filesystem.resolve(defectSubmitResult.getReportSubmitLocation());
    ZipInspector inspector = new ZipInspector(reportPath);
    inspector.assertFileContents(fileToBeIncluded, fileToBeIncludedContent);
  }

  @Test
  public void testAttachesReport() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRoot());
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    RageConfig config = RageConfig.builder()
        .build();
    Clock clock = new DefaultClock();
    DefectReporter reporter = new DefaultDefectReporter(
        filesystem,
        objectMapper,
        config,
        BuckEventBusFactory.newInstance(clock),
        clock);

    DefectSubmitResult defectSubmitResult = reporter.submitReport(
        DefectReport.builder()
            .setBuildEnvironmentDescription(TEST_ENV_DESCRIPTION)
            .setUserLocalConfiguration(TEST_USER_LOCAL_CONFIGURATION)
            .build());

    Path reportPath = filesystem.resolve(defectSubmitResult.getReportSubmitLocation());
    try (ZipFile zipFile = new ZipFile(reportPath.toFile())) {
      ZipEntry entry = zipFile.getEntry("report.json");
      JsonNode reportNode = objectMapper.readTree(zipFile.getInputStream(entry));
      assertThat(
          reportNode.get("buildEnvironmentDescription").get("user").asText(),
          Matchers.equalTo("test_user"));
      assertThat(
          reportNode.get("userLocalConfiguration").get("noBuckCheckPresent").asBoolean(),
          Matchers.equalTo(true));
    }
  }

}
