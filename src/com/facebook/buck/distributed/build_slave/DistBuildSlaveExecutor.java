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
import com.facebook.buck.core.build.distributed.synchronization.impl.NoOpRemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.build_slave.RemoteBuildModeRunner.FinalBuildStatusSetter;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public class DistBuildSlaveExecutor {

  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);
  private static final boolean KEEP_GOING = true;

  private final DistBuildSlaveExecutorArgs args;
  private final DelegateAndGraphsInitializer initializer;

  private final Object buildPreparationLock = new Object();
  private boolean buildPreparationCompleted = false;
  private Optional<Runnable> postPreparationCallback = Optional.empty();

  public DistBuildSlaveExecutor(DistBuildSlaveExecutorArgs args) {
    this.args = args;
    this.initializer =
        new DelegateAndGraphsInitializer(args.createDelegateAndGraphsInitiazerArgs());
  }

  /**
   * Register a callback to be executed after the preparation is complete and the actual build is
   * about to start. This function does not guarantee that the actual build has not started, but it
   * does guarantee that the preparation has finished when the callback is executed.
   */
  public void onBuildSlavePreparationCompleted(Runnable callback) {
    boolean alreadyCompleted;
    synchronized (buildPreparationLock) {
      this.postPreparationCallback = Optional.of(callback);
      alreadyCompleted = buildPreparationCompleted;
    }

    if (alreadyCompleted) {
      callback.run();
    }
  }

  public ExitCode buildAndReturnExitCode() throws IOException, InterruptedException {
    Optional<BuildId> clientBuildId = fetchClientBuildId();

    DistBuildModeRunner runner = null;
    if (DistBuildMode.COORDINATOR == args.getDistBuildMode()) {
      if (args.getDistBuildConfig().getSchedulingEnvironmentType()
              == SchedulingEnvironmentType.MIXED_HARDWARE
          && !args.getDistBuildConfig().getLowSpecMinionQueue().isPresent()) {
        args.getConsole()
            .printErrorText(
                "Stampede Low Spec Minion Queue name must be specified to used mixed hardware environment");
        return ExitCode.COMMANDLINE_ERROR;
      }

      runner =
          MultiSlaveBuildModeRunnerFactory.createCoordinator(
              initializer.getDelegateAndGraphs(),
              getTopLevelTargetsToBuild(),
              args.getDistBuildConfig(),
              args.getDistBuildService(),
              args.getStampedeId(),
              clientBuildId,
              false,
              args.getLogDirectoryPath(),
              args.getCoordinatorBuildRuleEventsPublisher(),
              args.getBuckEventBus(),
              args.getExecutorService(),
              args.getArtifactCacheFactory().remoteOnlyInstance(true, false),
              Futures.transform(
                  initializer.getDelegateAndGraphs(),
                  graphs -> {
                    SourcePathRuleFinder ruleFinder =
                        new SourcePathRuleFinder(
                            graphs.getActionGraphAndBuilder().getActionGraphBuilder());
                    return new ParallelRuleKeyCalculator<RuleKey>(
                        args.getExecutorService(),
                        new DefaultRuleKeyFactory(
                            new RuleKeyFieldLoader(args.getRuleKeyConfiguration()),
                            graphs.getCachingBuildEngineDelegate().getFileHashCache(),
                            DefaultSourcePathResolver.from(ruleFinder),
                            ruleFinder,
                            args.getRuleKeyCacheScope().getCache(),
                            Optional.empty()),
                        new DefaultRuleDepsCache(
                            graphs.getActionGraphAndBuilder().getActionGraphBuilder()),
                        (buckEventBus, rule) -> () -> {});
                  },
                  MoreExecutors.directExecutor()),
              args.getHealthCheckStatsTracker(),
              Optional.of(args.getTimingStatsTracker()),
              Optional.of(DistBuildUtil.generateMinionId(args.getBuildSlaveRunId())));
      return setPreparationCallbackAndRun(runner);
    }

    BuildExecutorArgs builderArgs = args.createBuilderArgs();
    try (ExecutionContext executionContext =
        LocalBuildExecutor.createExecutionContext(builderArgs)) {
      ListenableFuture<BuildExecutor> localBuildExecutor =
          createBuilder(builderArgs, executionContext);

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
                  args.getDistBuildConfig().getMinionType(),
                  args.getCapacityService(),
                  args.getBuildSlaveRunId(),
                  args.getRemoteCoordinatorAddress(),
                  OptionalInt.of(args.getRemoteCoordinatorPort()),
                  args.getDistBuildConfig(),
                  args.getMinionBuildProgressTracker(),
                  args.getBuckEventBus());
          break;

        case COORDINATOR_AND_MINION:
          runner =
              MultiSlaveBuildModeRunnerFactory.createCoordinatorAndMinion(
                  initializer.getDelegateAndGraphs(),
                  getTopLevelTargetsToBuild(),
                  args.getDistBuildConfig(),
                  args.getDistBuildService(),
                  args.getStampedeId(),
                  clientBuildId,
                  args.getCapacityService(),
                  args.getBuildSlaveRunId(),
                  localBuildExecutor,
                  args.getLogDirectoryPath(),
                  args.getCoordinatorBuildRuleEventsPublisher(),
                  args.getMinionBuildProgressTracker(),
                  args.getBuckEventBus(),
                  args.getExecutorService(),
                  args.getArtifactCacheFactory().remoteOnlyInstance(true, false),
                  args.getTimingStatsTracker(),
                  args.getHealthCheckStatsTracker());
          break;

        case COORDINATOR:
          throw new IllegalStateException("COORDINATOR mode should have already been handled.");

        default:
          LOG.error("Unknown distributed build mode [%s].", args.getDistBuildMode().toString());
          return ExitCode.FATAL_GENERIC;
      }

      return setPreparationCallbackAndRun(runner);
    }
  }

  private Optional<BuildId> fetchClientBuildId() {
    BuildJob job;
    try {
      job = args.getDistBuildService().getCurrentBuildJobState(args.getStampedeId());
    } catch (IOException e) {
      throw new RuntimeException("Failed to fetch build job object", e);
    }

    if (job.getBuckBuildUuid() != null && !job.getBuckBuildUuid().isEmpty()) {
      return Optional.of(new BuildId(job.getBuckBuildUuid()));
    } else {
      // TODO(nga): throw if unset after both client and server upgraded
      LOG.warn("buckBuildUuid field is not set; need to update buck client or stampede frontend");
      return Optional.empty();
    }
  }

  private ExitCode setPreparationCallbackAndRun(DistBuildModeRunner runner)
      throws IOException, InterruptedException {
    runner
        .getAsyncPrepFuture()
        .addListener(
            () -> {
              Optional<Runnable> callbackToRun;
              synchronized (buildPreparationLock) {
                buildPreparationCompleted = true;
                callbackToRun = postPreparationCallback;
              }
              callbackToRun.ifPresent(Runnable::run);
            },
            args.getExecutorService());

    return runner.runWithHeartbeatServiceAndReturnExitCode(args.getDistBuildConfig());
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

  private ListenableFuture<BuildExecutor> createBuilder(
      BuildExecutorArgs builderArgs, ExecutionContext executionContext) {
    return Futures.transform(
        initializer.getDelegateAndGraphs(),
        delegateAndGraphs ->
            new LocalBuildExecutor(
                builderArgs,
                executionContext,
                delegateAndGraphs.getActionGraphAndBuilder(),
                delegateAndGraphs.getCachingBuildEngineDelegate(),
                args.getExecutorService(),
                KEEP_GOING,
                true,
                false,
                args.getRuleKeyCacheScope(),
                Optional.empty(),
                Optional.empty(),
                // Only the client side build needs to synchronize, not the slave.
                // (as the co-ordinator synchronizes artifacts between slaves).
                new NoOpRemoteBuildRuleCompletionWaiter()),
        args.getExecutorService());
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
