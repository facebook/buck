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
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.rage.AbstractReport;
import com.facebook.buck.rage.AutomatedReport;
import com.facebook.buck.rage.DefaultDefectReporter;
import com.facebook.buck.rage.DefaultExtraInfoCollector;
import com.facebook.buck.rage.DefectSubmitResult;
import com.facebook.buck.rage.ExtraInfoCollector;
import com.facebook.buck.rage.InteractiveReport;
import com.facebook.buck.rage.RageBuckConfig;
import com.facebook.buck.rage.RageConfig;
import com.facebook.buck.rage.VcsInfoCollector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.versioncontrol.DefaultVersionControlCmdLineInterfaceFactory;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlCmdLineInterfaceFactory;
import com.google.common.base.Optional;

import org.kohsuke.args4j.Option;

import java.io.IOException;

public class RageCommand extends AbstractCommand {

  @Option(name = "--non-interactive", usage = "Force the command to run in non-interactive mode.")
  private boolean nonInteractive = false;

  @Option(name = "--gather-vcs-info", usage = "Gather information from the Version Control " +
      "System in non-interactive mode.")
  private boolean gatherVcsInfo = false;

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ProjectFilesystem filesystem = params.getCell().getFilesystem();
    BuckConfig buckConfig = params.getBuckConfig();
    RageConfig rageConfig = RageBuckConfig.create(buckConfig);
    DirtyPrintStreamDecorator stdOut = params.getConsole().getStdOut();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(params.getConsole());

    VersionControlCmdLineInterfaceFactory vcsFactory =
        new DefaultVersionControlCmdLineInterfaceFactory(
            params.getCell().getFilesystem().getRootPath(),
            new PrintStreamProcessExecutorFactory(),
            new VersionControlBuckConfig(buckConfig),
            buckConfig.getEnvironment());

    Optional<VcsInfoCollector> vcsInfoCollector =
        VcsInfoCollector.create(vcsFactory.createCmdLineInterface());

    ExtraInfoCollector extraInfoCollector =
        new DefaultExtraInfoCollector(rageConfig, filesystem, processExecutor);

    AbstractReport report;
    DefaultDefectReporter reporter = new DefaultDefectReporter(
        filesystem,
        params.getObjectMapper(),
        rageConfig,
        params.getBuckEventBus(),
        params.getClock());
    if (params.getConsole().getAnsi().isAnsiTerminal() && !nonInteractive) {
      report = new InteractiveReport(
          reporter,
          filesystem,
          stdOut,
          params.getStdIn(),
          params.getBuildEnvironmentDescription(),
          vcsInfoCollector,
          rageConfig,
          extraInfoCollector);
    } else {
      report = new AutomatedReport(
          reporter,
          filesystem,
          stdOut,
          params.getBuildEnvironmentDescription(),
          gatherVcsInfo ? vcsInfoCollector : Optional.absent(),
          rageConfig,
          extraInfoCollector);
    }

    Optional<DefectSubmitResult> defectSubmitResult = report.collectAndSubmitResult();
    report.presentDefectSubmitResult(defectSubmitResult);

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

  @Override
  public LogConfigSetup getLogConfig() {
    return LogConfigSetup.builder()
        .from(LogConfigSetup.DEFAULT_SETUP)
        .setLogFilePrefix("rage-")
        .build();
  }
}
