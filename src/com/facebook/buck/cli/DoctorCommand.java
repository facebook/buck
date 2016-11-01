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

import com.facebook.buck.doctor.DoctorReportHelper;
import com.facebook.buck.doctor.config.DoctorConfig;
import com.facebook.buck.doctor.config.DoctorEndpointRequest;
import com.facebook.buck.doctor.config.DoctorEndpointResponse;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.rage.BuildLogEntry;
import com.facebook.buck.rage.BuildLogHelper;
import com.facebook.buck.rage.DefaultDefectReporter;
import com.facebook.buck.rage.DefaultExtraInfoCollector;
import com.facebook.buck.rage.DefectSubmitResult;
import com.facebook.buck.rage.PopulatedReport;
import com.facebook.buck.rage.RageBuckConfig;
import com.facebook.buck.rage.RageConfig;
import com.facebook.buck.rage.UserInput;
import com.facebook.buck.rage.VcsInfoCollector;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.versioncontrol.DefaultVersionControlCmdLineInterfaceFactory;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlCmdLineInterfaceFactory;
import com.google.common.collect.ImmutableSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Optional;

public class DoctorCommand extends AbstractCommand {

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ProjectFilesystem filesystem = params.getCell().getFilesystem();
    BuildLogHelper buildLogHelper = new BuildLogHelper(filesystem, params.getObjectMapper());

    DoctorReportHelper helper = new DoctorReportHelper(
        params.getCell().getFilesystem(),
        new UserInput(
            params.getConsole().getStdOut(),
            new BufferedReader(new InputStreamReader(params.getStdIn()))),
        params.getConsole(),
        params.getObjectMapper(),
        params.getBuckConfig().getView(DoctorConfig.class));

    BuildLogEntry entry = helper.promptForBuild(new ArrayList<>(buildLogHelper.getBuildLogs()));
    Optional<DefectSubmitResult> rageResult = generateRageReport(params, entry);

    DoctorEndpointRequest request = helper.generateEndpointRequest(entry, rageResult);
    DoctorEndpointResponse response = helper.uploadRequest(request);

    helper.presentResponse(response);
    helper.presentRageResult(rageResult);

    return 0;
  }

  private Optional<DefectSubmitResult> generateRageReport(
      CommandRunnerParams params,
      BuildLogEntry entry) throws IOException, InterruptedException {
    RageConfig rageConfig = RageBuckConfig.create(params.getBuckConfig());
    VersionControlCmdLineInterfaceFactory vcsFactory =
        new DefaultVersionControlCmdLineInterfaceFactory(
            params.getCell().getFilesystem().getRootPath(),
            new PrintStreamProcessExecutorFactory(),
            new VersionControlBuckConfig(params.getBuckConfig()),
            params.getBuckConfig().getEnvironment());

    PopulatedReport report = new PopulatedReport(
        new DefaultDefectReporter(
            params.getCell().getFilesystem(),
            params.getObjectMapper(),
            rageConfig,
            params.getBuckEventBus(),
            params.getClock()),
        params.getCell().getFilesystem(),
        params.getConsole().getStdOut(),
        params.getBuildEnvironmentDescription(),
        VcsInfoCollector.create(vcsFactory.createCmdLineInterface()),
        rageConfig,
        new DefaultExtraInfoCollector(
            rageConfig,
            params.getCell().getFilesystem(),
            new DefaultProcessExecutor(params.getConsole())),
        ImmutableSet.of(entry));

    return report.collectAndSubmitResult();
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "debug and fix issues of Buck commands";
  }

  @Override
  public LogConfigSetup getLogConfig() {
    return LogConfigSetup.builder()
        .from(LogConfigSetup.DEFAULT_SETUP)
        .setLogFilePrefix("doctor-")
        .build();
  }

}
