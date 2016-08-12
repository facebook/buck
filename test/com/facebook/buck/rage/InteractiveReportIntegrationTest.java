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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.TestBuildEnvironmentDescription;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.versioncontrol.NoOpCmdLineInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;


public class InteractiveReportIntegrationTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testReport() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "interactive_report", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem filesystem = workspace.asCell().getFilesystem();
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    BuckConfig buckConfig = workspace.asCell().getBuckConfig();
    DefectReporter defectReporter = new DefaultDefectReporter(
        filesystem,
        objectMapper,
        RageBuckConfig.create(buckConfig));
    CapturingPrintStream outputStream = new CapturingPrintStream();
    ExtraInfoCollector extraInfoCollector = new ExtraInfoCollector() {
      @Override
      public Optional<ExtraInfoResult> run()
          throws IOException, InterruptedException, ExtraInfoExecutionException {
        return Optional.absent();
      }
    };
    ByteArrayInputStream inputStream =
        new ByteArrayInputStream("0,1\nreport text\n".getBytes("UTF-8"));
    InteractiveReport interactiveReport =
        new InteractiveReport(
            defectReporter,
            filesystem,
            outputStream,
            inputStream,
            TestBuildEnvironmentDescription.INSTANCE,
            VcsInfoCollector.create(new NoOpCmdLineInterface()),
            extraInfoCollector);
    DefectSubmitResult defectSubmitResult = interactiveReport.collectAndSubmitResult();
    Path reportFile = filesystem.resolve(defectSubmitResult.getReportLocalLocation().get());

    ZipInspector zipInspector = new ZipInspector(reportFile);
    zipInspector.assertFileExists("report.json");
    zipInspector.assertFileExists("buck-out/log/" +
        "2016-06-21_16h16m24s_buildcommand_ac8bd626-6137-4747-84dd-5d4f215c876c/buck.log");
    zipInspector.assertFileExists("buck-out/log/" +
        "2016-06-21_16h18m51s_autodepscommand_d09893d5-b11e-4e3f-a5bf-70c60a06896e/buck.log");
  }
}
