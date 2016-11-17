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

import com.facebook.buck.distributed.BuildJobStateSerializer;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.Console;
import com.google.common.base.Stopwatch;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class DistBuildRunCommand extends AbstractDistBuildCommand {

  public static final String BUILD_STATE_FILE_ARG_NAME = "--build-state-file";
  public static final String BUILD_STATE_FILE_ARG_USAGE = "File containing the BuildStateJob data.";

  @Nullable
  @Option(name = BUILD_STATE_FILE_ARG_NAME, usage = BUILD_STATE_FILE_ARG_USAGE)
  private String buildStateFile;

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public String getShortDescription() {
    return "runs a distributed build in the current machine (experimental)";
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Console console = params.getConsole();
    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      Pair<BuildJobState, String> jobStateAndBuildName = getBuildJobStateAndBuildName(
          params.getCell().getFilesystem(),
          console,
          service);
      BuildJobState jobState = jobStateAndBuildName.getFirst();
      String buildName = jobStateAndBuildName.getSecond();

      console.getStdOut().println(String.format(
          "BuildJob depends on a total of [%d] input deps.",
          jobState.getFileHashesSize()));
      try (CommandThreadManager pool = new CommandThreadManager(
          getClass().getName(),
          getConcurrencyLimit(params.getBuckConfig()))) {
        DistBuildSlaveExecutor distBuildExecutor = DistBuildFactory.createDistBuildExecutor(
            jobState,
            params,
            pool.getExecutor(),
            service);
        int returnCode = distBuildExecutor.buildAndReturnExitCode();
        console.printSuccess(String.format(
            "Successfully ran distributed build [%s] in [%d millis].",
            buildName,
            stopwatch.elapsed(
                TimeUnit.MILLISECONDS)));
        return returnCode;
      }
    }
  }

  public Pair<BuildJobState, String> getBuildJobStateAndBuildName(
      ProjectFilesystem filesystem,
      Console console,
      DistBuildService service) throws IOException {

    if (buildStateFile != null) {
      Path buildStateFilePath = Paths.get(buildStateFile);
      console.getStdOut().println(String.format(
          "Retrieving BuildJobState for from file [%s].",
          buildStateFilePath));
      return new Pair<>(
          BuildJobStateSerializer.deserialize(filesystem.newFileInputStream(buildStateFilePath)),
          String.format("LocalFile=[%s]", buildStateFile));
    } else {
      BuildId buildId = getBuildId();
      console.getStdOut().println(String.format(
          "Retrieving BuildJobState for build [%s].",
          buildId));
      return new Pair<>(
          service.fetchBuildJobState(buildId),
          String.format("DistBuild=[%s]", buildId.toString()));
    }
  }
}
