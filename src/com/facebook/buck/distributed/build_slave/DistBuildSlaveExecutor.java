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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.command.LocalBuildExecutor;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.build_slave.RemoteBuildModeRunner.FinalBuildStatusSetter;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DistBuildSlaveExecutor {

  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);
  private static final boolean KEEP_GOING = true;

  private final DistBuildSlaveExecutorArgs args;
  private final DelegateAndGraphsInitializer initializer;

  public DistBuildSlaveExecutor(DistBuildSlaveExecutorArgs args) {
    this.args = args;
    this.initializer =
        new DelegateAndGraphsInitializer(args.createDelegateAndGraphsInitiazerArgs());
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    DistBuildModeRunner runner = null;
    if (DistBuildMode.COORDINATOR == args.getDistBuildMode()) {
      runner =
          MultiSlaveBuildModeRunnerFactory.createCoordinator(
              initializer.getActionGraphAndResolver(),
              getTopLevelTargetsToBuild(),
              args.getDistBuildConfig(),
              args.getDistBuildService(),
              args.getStampedeId(),
              false,
              args.getLogDirectoryPath(),
              args.getBuildRuleFinishedPublisher());
      return runWithHeartbeatService(runner);
    }

    BuildExecutorArgs builderArgs = args.createBuilderArgs();
    try (ExecutionContext executionContext =
        LocalBuildExecutor.createExecutionContext(builderArgs)) {
      BuildExecutor localBuildExecutor = createBuilder(builderArgs, executionContext);

      switch (args.getDistBuildMode()) {
        case REMOTE_BUILD:
          runner =
              new RemoteBuildModeRunner(
                  localBuildExecutor,
                  args.getState().getRemoteState().getTopLevelTargets(),
                  createRemoteBuildFinalBuildStatusSetter(),
                  args.getDistBuildService(),
                  args.getStampedeId());
          break;

        case MINION:
          runner =
              MultiSlaveBuildModeRunnerFactory.createMinion(
                  localBuildExecutor,
                  args.getDistBuildService(),
                  args.getStampedeId(),
                  args.getBuildSlaveRunId(),
                  args.getRemoteCoordinatorAddress(),
                  OptionalInt.of(args.getRemoteCoordinatorPort()),
                  args.getDistBuildConfig());
          break;

        case COORDINATOR_AND_MINION:
          runner =
              MultiSlaveBuildModeRunnerFactory.createCoordinatorAndMinion(
                  initializer.getActionGraphAndResolver(),
                  getTopLevelTargetsToBuild(),
                  args.getDistBuildConfig(),
                  args.getDistBuildService(),
                  args.getStampedeId(),
                  args.getBuildSlaveRunId(),
                  localBuildExecutor,
                  args.getLogDirectoryPath(),
                  args.getBuildRuleFinishedPublisher());
          break;

        case COORDINATOR:
          throw new IllegalStateException("COORDINATOR mode should have already been handled.");

        default:
          LOG.error("Unknown distributed build mode [%s].", args.getDistBuildMode().toString());
          return -1;
      }
    }

    return runWithHeartbeatService(runner);
  }

  private int runWithHeartbeatService(DistBuildModeRunner runner)
      throws IOException, InterruptedException {
    try (HeartbeatService service =
        new HeartbeatService(args.getDistBuildConfig().getHearbeatServiceRateMillis())) {
      return runner.runAndReturnExitCode(service);
    }
  }

  private FinalBuildStatusSetter createRemoteBuildFinalBuildStatusSetter() {
    return new FinalBuildStatusSetter() {
      @Override
      public void setFinalBuildStatus(int exitCode) throws IOException {
        BuildStatus status = BuildStatusUtil.exitCodeToBuildStatus(exitCode);
        String message =
            String.format(
                "RemoteBuilder [%s] exited with code=[%d] and status=[%s].",
                HostnameFetching.getHostname(), exitCode, status.toString());
        args.getDistBuildService().setFinalBuildStatus(args.getStampedeId(), status, message);
      }
    };
  }

  private BuildExecutor createBuilder(
      BuildExecutorArgs builderArgs, ExecutionContext executionContext) {
    Supplier<BuildExecutor> builderSupplier =
        () -> {
          DelegateAndGraphs delegateAndGraphs = null;
          try {
            delegateAndGraphs = initializer.getDelegateAndGraphs().get();
          } catch (InterruptedException | ExecutionException e) {
            String msg = String.format("Failed to get the DelegateAndGraphs.");
            LOG.error(e, msg);
            throw new RuntimeException(msg, e);
          }
          return new LocalBuildExecutor(
              builderArgs,
              executionContext,
              delegateAndGraphs.getActionGraphAndResolver(),
              delegateAndGraphs.getCachingBuildEngineDelegate(),
              args.getExecutorService(),
              KEEP_GOING,
              true,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              // Only the client side build needs to synchronize, not the slave.
              // (as the co-ordinator synchronizes artifacts between slaves).
              new NoOpRemoteBuildRuleCompletionWaiter());
        };
    return new LazyInitBuilder(builderSupplier);
  }

  private List<BuildTarget> getTopLevelTargetsToBuild() {
    return args.getState()
        .getRemoteState()
        .getTopLevelTargets()
        .stream()
        .map(
            target ->
                BuildTargetParser.fullyQualifiedNameToBuildTarget(
                    args.getRootCell().getCellPathResolver(), target))
        .collect(Collectors.toList());
  }
}
