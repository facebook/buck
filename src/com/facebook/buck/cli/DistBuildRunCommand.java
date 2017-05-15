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
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.DistBuildSlaveEventBusListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public class DistBuildRunCommand extends AbstractDistBuildCommand {

  public static final String BUILD_STATE_FILE_ARG_NAME = "--build-state-file";
  public static final String BUILD_STATE_FILE_ARG_USAGE = "File containing the BuildStateJob data.";

  @Nullable
  @Option(name = BUILD_STATE_FILE_ARG_NAME, usage = BUILD_STATE_FILE_ARG_USAGE)
  private String buildStateFile;

  @Nullable
  @Option(
    name = "--coordinator-port",
    usage = "The local port that the build coordinator thrift server will listen on."
  )
  private int coordinatorPort = -1;

  @Nullable
  @Option(name = "--build-mode", usage = "The mode in which the distributed build is going to run.")
  private DistBuildMode distBuildMode = DistBuildMode.REMOTE_BUILD;

  @Nullable
  @Option(
    name = "--global-cache-dir",
    usage = "Full path to an existing directory that will contain a global cache across builds."
  )
  private Path globalCacheDir;

  private static final String RUN_ID_ARG_NAME = "--buildslave-run-id";

  @Nullable
  @Option(name = RUN_ID_ARG_NAME, usage = "Stampede RunId for this instance of BuildSlave.")
  private String runId;

  @Nullable private DistBuildSlaveEventBusListener slaveEventListener;

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
      if (slaveEventListener != null) {
        slaveEventListener.setDistBuildService(service);
      }

      try {
        Pair<BuildJobState, String> jobStateAndBuildName =
            getBuildJobStateAndBuildName(params.getCell().getFilesystem(), console, service);
        BuildJobState jobState = jobStateAndBuildName.getFirst();
        String buildName = jobStateAndBuildName.getSecond();

        console
            .getStdOut()
            .println(
                String.format(
                    "BuildJob depends on a total of [%d] input deps.",
                    jobState.getFileHashesSize()));
        try (CommandThreadManager pool =
            new CommandThreadManager(
                getClass().getName(), getConcurrencyLimit(params.getBuckConfig()))) {
          DistBuildSlaveExecutor distBuildExecutor =
              DistBuildFactory.createDistBuildExecutor(
                  jobState,
                  params,
                  pool.getExecutor(),
                  service,
                  Preconditions.checkNotNull(distBuildMode),
                  coordinatorPort,
                  getStampedeIdOptional(),
                  getGlobalCacheDirOptional());
          int returnCode = distBuildExecutor.buildAndReturnExitCode();
          if (slaveEventListener != null) {
            slaveEventListener.publishBuildSlaveFinishedEvent(params.getBuckEventBus(), returnCode);
          }
          if (returnCode == 0) {
            console.printSuccess(
                String.format(
                    "Successfully ran distributed build [%s] in [%d millis].",
                    buildName, stopwatch.elapsed(TimeUnit.MILLISECONDS)));
          } else {
            console.printErrorText(
                "Failed distributed build [%s] in [%d millis].",
                buildName, stopwatch.elapsed(TimeUnit.MILLISECONDS));
          }
          return returnCode;
        }
      } catch (HumanReadableException e) {
        logBuildFailureEvent(e.getHumanReadableErrorMessage(), slaveEventListener);
        throw e;
      } catch (Exception e) {
        logBuildFailureEvent(e.getMessage(), slaveEventListener);
        throw e;
      }
    }
  }

  /** Logs a severe error message prefixed with {@code BUILD SLAVE FAILED} to the frontend. */
  private static void logBuildFailureEvent(
      String failureMessage, @Nullable DistBuildSlaveEventBusListener slaveEventListener) {
    if (slaveEventListener != null) {
      slaveEventListener.logEvent(
          ConsoleEvent.severe(String.format("BUILD SLAVE FAILED: %s", failureMessage)));
    }
  }

  public Pair<BuildJobState, String> getBuildJobStateAndBuildName(
      ProjectFilesystem filesystem, Console console, DistBuildService service) throws IOException {

    if (buildStateFile != null) {
      Path buildStateFilePath = Paths.get(buildStateFile);
      console
          .getStdOut()
          .println(
              String.format("Retrieving BuildJobState for from file [%s].", buildStateFilePath));
      return new Pair<>(
          BuildJobStateSerializer.deserialize(filesystem.newFileInputStream(buildStateFilePath)),
          String.format("LocalFile=[%s]", buildStateFile));
    } else {
      StampedeId stampedeId = getStampedeId();
      console
          .getStdOut()
          .println(String.format("Retrieving BuildJobState for build [%s].", stampedeId));
      return new Pair<>(
          service.fetchBuildJobState(stampedeId),
          String.format("DistBuild=[%s]", stampedeId.toString()));
    }
  }

  private Optional<Path> getGlobalCacheDirOptional() {
    if (globalCacheDir == null) {
      return Optional.empty();
    } else {
      if (!Files.isDirectory(globalCacheDir)) {
        try {
          Files.createDirectories(globalCacheDir);
        } catch (IOException exception) {
          throw new HumanReadableException(
              exception,
              "Directory passed for --global-cache-dir cannot be created. value=[%s]",
              globalCacheDir.toString());
        }
      }

      return Optional.of(globalCacheDir);
    }
  }

  private void checkArgs() {
    if (buildStateFile == null && (!getStampedeIdOptional().isPresent() || runId == null)) {
      throw new HumanReadableException(
          String.format(
              "Options '%s' and '%s' are both required when '%s' is not provided.",
              STAMPEDE_ID_ARG_NAME, RUN_ID_ARG_NAME, BUILD_STATE_FILE_ARG_NAME));
    }
  }

  private void initEventListener() {
    if (slaveEventListener == null) {
      checkArgs();
      RunId runId = new RunId();
      runId.setId(this.runId);

      ScheduledExecutorService networkScheduler = Executors.newScheduledThreadPool(1);
      slaveEventListener =
          new DistBuildSlaveEventBusListener(
              getStampedeId(), runId, new DefaultClock(), networkScheduler);
    }
  }

  @Override
  public Iterable<BuckEventListener> getEventListeners() {
    if (buildStateFile == null) {
      initEventListener();
      return ImmutableList.of(slaveEventListener);
    } else {
      return ImmutableList.of();
    }
  }
}
