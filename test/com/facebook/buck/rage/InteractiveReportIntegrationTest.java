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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

public class InteractiveReportIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testReport() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "interactive_report", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem filesystem = workspace.asCell().getFilesystem();
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    DefectReporter defectReporter = new DefectReporter(
        filesystem,
        objectMapper);
    CapturingPrintStream outputStream = new CapturingPrintStream();
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream("report text\n0,1\n".getBytes("UTF-8"));
    InteractiveReport interactiveReport =
        new InteractiveReport(
            defectReporter,
            filesystem,
            outputStream,
            inputStream,
            TestBuildEnvironmentDescription.INSTANCE);
    DefectSubmitResult defectSubmitResult = interactiveReport.collectAndSubmitResult();
    Path reportFile = filesystem.resolve(defectSubmitResult.getReportLocalLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists("report.json");
    zipInspector.assertFileExists("buck-out/log/buck-0.log");
    zipInspector.assertFileExists("buck-out/log/buck-1.log");
  }
}
