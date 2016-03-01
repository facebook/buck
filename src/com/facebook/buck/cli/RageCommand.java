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

package com.facebook.buck.cli;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rage.AutomatedReport;
import com.facebook.buck.rage.DefectReporter;
import com.facebook.buck.rage.DefectSubmitResult;
import com.facebook.buck.rage.InteractiveReport;
import com.facebook.buck.util.DirtyPrintStreamDecorator;

import org.kohsuke.args4j.Option;

import java.io.IOException;

public class RageCommand extends AbstractCommand {

  @Option(name = "--non-interactive", usage = "Force the command to run in non-interactive mode.")
  private boolean nonInteractive = false;

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ProjectFilesystem filesystem = params.getCell().getFilesystem();
    DirtyPrintStreamDecorator stdOut = params.getConsole().getStdOut();

    DefectSubmitResult defectSubmitResult;
    if (params.getConsole().getAnsi().isAnsiTerminal() && !nonInteractive) {
      InteractiveReport interactiveReport = new InteractiveReport(
          new DefectReporter(filesystem, params.getObjectMapper()),
          filesystem,
          stdOut,
          params.getStdIn(),
          params.getBuildEnvironmentDescription());
      defectSubmitResult = interactiveReport.collectAndSubmitResult();
    } else {
      AutomatedReport automatedReport = new AutomatedReport(
          new DefectReporter(filesystem, params.getObjectMapper()),
          filesystem,
          stdOut,
          params.getBuildEnvironmentDescription());
      defectSubmitResult = automatedReport.collectAndSubmitResult();
    }

    stdOut.printf("Report saved to %s\n", defectSubmitResult.getReportSubmitLocation());
    if (defectSubmitResult.getReportSubmitMessage().isPresent()) {
      stdOut.println(defectSubmitResult.getReportSubmitMessage().get());
    }
    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "create a defect report";
  }
}
