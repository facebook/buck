/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.build_client.BuildSlaveLogsMaterializer;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Download and materializes logs from all servers involved in a ditributed build. */
public class DistBuildLogsCommand extends AbstractDistBuildCommand {

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "retrieves all logs from the remote BuildSlave servers";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    Console console = params.getConsole();
    PrintStream stdout = console.getStdOut();
    StampedeId stampedeId = getStampedeId();

    try (DistBuildService service = DistBuildFactory.newDistBuildService(params)) {
      stdout.println(
          String.format("Fetching build information for StampedeId=[%s].", stampedeId.getId()));
      Stopwatch stopwatch = Stopwatch.createStarted();
      BuildJob buildJob = service.getCurrentBuildJobState(getStampedeId());
      stdout.println(
          String.format(
              "Successfully downloaded build information in [%d millis].",
              stopwatch.elapsed(TimeUnit.MILLISECONDS)));

      stopwatch.reset().start();
      List<BuildSlaveRunId> buildSlaves =
          buildJob
              .getBuildSlaves()
              .stream()
              .map(x -> x.getBuildSlaveRunId())
              .collect(Collectors.toList());
      stdout.println(
          String.format(
              "Materializing logs for [%d] BuildSlaves. (%s)",
              buildSlaves.size(), Joiner.on(", ").join(buildSlaves)));
      Path logDir = params.getInvocationInfo().get().getLogDirectoryPath();
      ProjectFilesystem filesystem = params.getCell().getFilesystem();
      BuildSlaveLogsMaterializer materializer =
          new BuildSlaveLogsMaterializer(service, filesystem, logDir);
      List<BuildSlaveRunId> notMaterialized =
          materializer.fetchAndMaterializeAvailableLogs(buildJob.getStampedeId(), buildSlaves);

      if (notMaterialized.isEmpty()) {
        console.printSuccess(
            String.format(
                "Successfully materialized all logs into [%s] in [%d millis].",
                logDir.toAbsolutePath().toString(), stopwatch.elapsed(TimeUnit.MILLISECONDS)));
        return ExitCode.SUCCESS;
      } else if (notMaterialized.size() == buildSlaves.size()) {
        console.printErrorText(
            String.format(
                "Failed to materialize all logs. Duration=[%d millis].",
                stopwatch.elapsed(TimeUnit.MILLISECONDS)));
        // TODO: buck(team) proper disambiguate between user errors and fatals
        return ExitCode.BUILD_ERROR;
      } else {
        stdout.println(
            console
                .getAnsi()
                .asWarningText(
                    String.format(
                        "Materialized [%d] out of [%d] logs into [%s] in [%d millis].",
                        buildSlaves.size() - notMaterialized.size(),
                        buildSlaves.size(),
                        logDir.toAbsolutePath().toString(),
                        stopwatch.elapsed(TimeUnit.MILLISECONDS))));
        return ExitCode.BUILD_ERROR;
      }
    }
  }
}
