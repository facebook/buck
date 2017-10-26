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

package com.facebook.buck.distributed;

import com.facebook.buck.command.Builder;
import com.facebook.buck.command.BuilderArgs;
import com.facebook.buck.command.LocalBuilder;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DistBuildSlaveExecutor {

  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);
  private static final String LOCALHOST_ADDRESS = "localhost";
  private static final boolean KEEP_GOING = true;

  private final DistBuildSlaveExecutorArgs args;
  private final DelegateAndGraphsInitializer initializer;

  public DistBuildSlaveExecutor(DistBuildSlaveExecutorArgs args) {
    this.args = args;
    this.initializer =
        new DelegateAndGraphsInitializer(args.createDelegateAndGraphsInitiazerArgs());
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    if (DistBuildMode.COORDINATOR == args.getDistBuildMode()) {
      return newCoordinatorMode(getFreePortForCoordinator(), false).runAndReturnExitCode();
    }

    DistBuildModeRunner runner = null;
    BuilderArgs builderArgs = args.createBuilderArgs();
    try (ExecutionContext executionContext = LocalBuilder.createExecutionContext(builderArgs)) {
      Builder localBuilder =
          new LocalBuilder(
              builderArgs,
              executionContext,
              initializer.getDelegateAndGraphs().getActionGraphAndResolver(),
              initializer.getDelegateAndGraphs().getCachingBuildEngineDelegate(),
              args.getArtifactCache(),
              args.getExecutorService(),
              KEEP_GOING,
              Optional.empty(),
              Optional.empty(),
              Optional.empty());

      switch (args.getDistBuildMode()) {
        case REMOTE_BUILD:
          runner =
              new RemoteBuildModeRunner(
                  localBuilder,
                  args.getState().getRemoteState().getTopLevelTargets(),
                  exitCode ->
                      args.getDistBuildService()
                          .setFinalBuildStatus(
                              args.getStampedeId(),
                              BuildStatusUtil.exitCodeToBuildStatus(exitCode)));
          break;

        case MINION:
          runner =
              newMinionMode(
                  localBuilder,
                  args.getRemoteCoordinatorAddress(),
                  args.getRemoteCoordinatorPort());
          break;

        case COORDINATOR_AND_MINION:
          int localCoordinatorPort = getFreePortForCoordinator();
          runner =
              new CoordinatorAndMinionModeRunner(
                  newCoordinatorMode(localCoordinatorPort, true),
                  newMinionMode(localBuilder, LOCALHOST_ADDRESS, localCoordinatorPort));
          break;

        case COORDINATOR:
          throw new IllegalStateException("COORDINATOR mode should have already been handled.");

        default:
          LOG.error("Unknown distributed build mode [%s].", args.getDistBuildMode().toString());
          return -1;
      }
    }
    return runner.runAndReturnExitCode();
  }

  private MinionModeRunner newMinionMode(
      Builder localBuilder, String coordinatorAddress, int coordinatorPort) {
    MinionModeRunner.BuildCompletionChecker checker =
        () -> {
          BuildJob job = args.getDistBuildService().getCurrentBuildJobState(args.getStampedeId());
          return BuildStatusUtil.isBuildComplete(job.getStatus());
        };

    return new MinionModeRunner(
        coordinatorAddress,
        coordinatorPort,
        localBuilder,
        args.getStampedeId(),
        args.getBuildSlaveRunId(),
        args.getBuildThreadCount(),
        checker);
  }

  private CoordinatorModeRunner newCoordinatorMode(
      int coordinatorPort, boolean isLocalMinionAlsoRunning) {
    final CellPathResolver cellNames = args.getState().getRootCell().getCellPathResolver();
    List<BuildTarget> targets =
        args.getState()
            .getRemoteState()
            .getTopLevelTargets()
            .stream()
            .map(target -> BuildTargetParser.fullyQualifiedNameToBuildTarget(cellNames, target))
            .collect(Collectors.toList());
    BuildTargetsQueue queue =
        BuildTargetsQueue.newQueue(
            initializer.getDelegateAndGraphs().getActionGraphAndResolver().getResolver(), targets);
    Optional<String> minionQueue = args.getDistBuildConfig().getMinionQueue();
    Preconditions.checkArgument(
        minionQueue.isPresent(),
        "Minion queue name is missing to be able to run in Coordinator mode.");
    ThriftCoordinatorServer.EventListener listener =
        new CoordinatorEventListener(
            args.getDistBuildService(),
            args.getStampedeId(),
            minionQueue.get(),
            isLocalMinionAlsoRunning);
    return new CoordinatorModeRunner(coordinatorPort, queue, args.getStampedeId(), listener);
  }

  public static int getFreePortForCoordinator() throws IOException {
    // Passing argument 0 to ServerSocket will allocate a new free random port.
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
