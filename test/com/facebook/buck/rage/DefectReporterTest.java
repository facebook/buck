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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.TriState;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testAttachesPaths() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    DefectReporter reporter = new DefectReporter(
        filesystem,
        ObjectMappers.newDefaultInstance());

    Path fileToBeIncluded = Paths.get("FileToBeIncluded.txt");
    filesystem.touch(fileToBeIncluded);
    String fileToBeIncludedContent = "testcontentbehere";
    filesystem.writeContentsToPath(fileToBeIncludedContent, fileToBeIncluded);

    DefectSubmitResult defectSubmitResult = reporter.submitReport(
        DefectReport.builder()
            .setBuildEnvironmentDescription(TEST_ENV_DESCRIPTION)
            .setIncludedPaths(fileToBeIncluded)
            .build());

    Path reportPath = filesystem.resolve(defectSubmitResult.getReportLocalLocation().get());
    ZipInspector inspector = new ZipInspector(reportPath);
    inspector.assertFileContents(fileToBeIncluded.toString(), fileToBeIncludedContent);
  }

  @Test
  public void testAttachesReport() throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    DefectReporter reporter = new DefectReporter(
        filesystem,
        objectMapper);

    DefectSubmitResult defectSubmitResult = reporter.submitReport(
        DefectReport.builder()
            .setBuildEnvironmentDescription(TEST_ENV_DESCRIPTION)
            .build());

    Path reportPath = filesystem.resolve(defectSubmitResult.getReportLocalLocation().get());
    try (ZipFile zipFile = new ZipFile(reportPath.toFile())) {
      ZipEntry entry = zipFile.getEntry("report.json");
      JsonNode reportNode = objectMapper.readTree(zipFile.getInputStream(entry));
      assertThat(
          reportNode.get("buildEnvironmentDescription").get("user").asText(),
          Matchers.equalTo("test_user"));
    }
  }

}
