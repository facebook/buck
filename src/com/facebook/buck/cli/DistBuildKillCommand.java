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

import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.base.Stopwatch;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/** Kills a stampede/distributed build. */
public class DistBuildKillCommand extends AbstractDistBuildCommand {

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "kills a distributed build.";
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
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
              "The build is currently in status [%s:%s].",
              buildJob.getStatus().toString(), buildJob.getStatusMessage()));
      if (BuildStatusUtil.isTerminalBuildStatus(buildJob.getStatus())) {
        console.printSuccess(
            String.format(
                "Build is already finished so doing nothing. Took [%d millis] to execute.",
                stopwatch.elapsed(TimeUnit.MILLISECONDS)));
        // Returning SUCCESS instead of NOTHING_TO_DO to possibly avoid breaking some contract
        return ExitCode.SUCCESS;
      }

      String statusMessage =
          String.format(
              "Build killed via 'buck distbuild kill' command by user=[%s] from host=[%s].",
              System.getProperty("user.name"), HostnameFetching.getHostname());
      stdout.println(
          String.format("Killing the build and setting statusMessage to [%s].", statusMessage));

      service.setFinalBuildStatus(stampedeId, BuildStatus.FAILED, statusMessage);
      console.printSuccess(
          String.format(
              "Successfully killed the build in [%d millis].",
              stopwatch.elapsed(TimeUnit.MILLISECONDS)));
      return ExitCode.SUCCESS;
    }
  }
}
