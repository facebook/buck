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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Factory for multi-slave implementations of DistBuildModeRunners. */
public class MultiSlaveBuildModeRunnerFactory {

  private static final String LOCALHOST_ADDRESS = "localhost";

  private static BuildTargetsQueue createBuildQueue(
      ActionGraphAndResolver actionGraphAndResolver, List<BuildTarget> topLevelTargetsToBuild) {
    BuildTargetsQueue queue =
        BuildTargetsQueue.newQueue(actionGraphAndResolver.getResolver(), topLevelTargetsToBuild);
    return queue;
  }

  /**
   * Create a {@link CoordinatorModeRunner}.
   *
   * @param isLocalMinionAlsoRunning - are we going to have a local minion working on this build?
   * @return a new instance of the {@link CoordinatorModeRunner}.
   */
  public static CoordinatorModeRunner createCoordinator(
      ListenableFuture<ActionGraphAndResolver> actionGraphAndResolverFuture,
      List<BuildTarget> topLevelTargetsToBuild,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      boolean isLocalMinionAlsoRunning,
      Path logDirectoryPath) {
    ListenableFuture<BuildTargetsQueue> queue =
        Futures.transform(
            actionGraphAndResolverFuture, x -> createBuildQueue(x, topLevelTargetsToBuild));
    Optional<String> minionQueue = distBuildConfig.getMinionQueue();
    Preconditions.checkArgument(
        minionQueue.isPresent(),
        "Minion queue name is missing to be able to run in Coordinator mode.");
    ThriftCoordinatorServer.EventListener listener =
        new CoordinatorEventListener(
            distBuildService, stampedeId, minionQueue.get(), isLocalMinionAlsoRunning);
    return new CoordinatorModeRunner(queue, stampedeId, listener, logDirectoryPath);
  }

  /**
   * Create a {@link MinionModeRunner}.
   *
   * @param localBuildExecutor - instance of {@link BuildExecutor} object to be used by the minion.
   * @return a new instance of the {@link MinionModeRunner}.
   */
  public static MinionModeRunner createMinion(
      BuildExecutor localBuildExecutor,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      BuildSlaveRunId buildSlaveRunId,
      String coordinatorAddress,
      OptionalInt coordinatorPort,
      DistBuildConfig distBuildConfig) {
    MinionModeRunner.BuildCompletionChecker checker =
        () -> {
          BuildJob job = distBuildService.getCurrentBuildJobState(stampedeId);
          return BuildStatusUtil.isTerminalBuildStatus(job.getStatus());
        };

    return new MinionModeRunner(
        coordinatorAddress,
        coordinatorPort,
        localBuildExecutor,
        stampedeId,
        buildSlaveRunId,
        distBuildConfig
            .getBuckConfig()
            .getView(ResourcesConfig.class)
            .getConcurrencyLimit()
            .threadLimit,
        checker,
        distBuildConfig.getMinionPollLoopIntervalMillis());
  }

  /**
   * Create a {@link CoordinatorAndMinionModeRunner}.
   *
   * @param localBuildExecutor - instance of {@link BuildExecutor} object to be used by the minion.
   * @return a new instance of the {@link CoordinatorAndMinionModeRunner}.
   */
  public static CoordinatorAndMinionModeRunner createCoordinatorAndMinion(
      ListenableFuture<ActionGraphAndResolver> actionGraphAndResolverFuture,
      List<BuildTarget> topLevelTargets,
      DistBuildConfig distBuildConfig,
      DistBuildService distBuildService,
      StampedeId stampedeId,
      BuildSlaveRunId buildSlaveRunId,
      BuildExecutor localBuildExecutor,
      Path logDirectoryPath) {
    return new CoordinatorAndMinionModeRunner(
        createCoordinator(
            actionGraphAndResolverFuture,
            topLevelTargets,
            distBuildConfig,
            distBuildService,
            stampedeId,
            true,
            logDirectoryPath),
        createMinion(
            localBuildExecutor,
            distBuildService,
            stampedeId,
            buildSlaveRunId,
            LOCALHOST_ADDRESS,
            OptionalInt.empty(),
            distBuildConfig));
  }
}
