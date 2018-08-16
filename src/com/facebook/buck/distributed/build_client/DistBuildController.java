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

package com.facebook.buck.distributed.build_client;

import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildEvent.DistBuildStarted;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistLocalBuildMode;
import com.facebook.buck.distributed.DistributedExitCode;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** High level controls the distributed build. */
public class DistBuildController {
  private static final Logger LOG = Logger.get(DistBuildController.class);
  private static final int MISMATCHING_RULE_PRINT_LIMIT = 100;

  private final BuckEventBus eventBus;
  private final ConsoleEventsDispatcher consoleEventsDispatcher;
  private final PreBuildPhase preBuildPhase;
  private final BuildPhase buildPhase;
  private final PostBuildPhase postBuildPhase;
  private final Console console;
  private final DistBuildStarted startedEvent;

  private final ListenableFuture<BuildJobState> asyncJobState;
  private final AtomicReference<StampedeId> stampedeIdReference;

  public DistBuildController(DistBuildControllerArgs args) {
    this.stampedeIdReference = args.getStampedeIdReference();
    this.eventBus = args.getBuckEventBus();
    this.startedEvent = args.getDistBuildStartedEvent();
    this.consoleEventsDispatcher = new ConsoleEventsDispatcher(eventBus);
    this.asyncJobState = args.getAsyncJobState();
    this.preBuildPhase =
        new PreBuildPhase(
            args.getDistBuildService(),
            args.getDistBuildClientStats(),
            args.getAsyncJobState(),
            args.getDistBuildCellIndexer(),
            args.getBuckVersion(),
            args.getBuilderExecutorArgs(),
            args.getTopLevelTargets(),
            args.getBuildGraphs(),
            args.getBuildLabel());
    this.buildPhase =
        new BuildPhase(
            args.getBuilderExecutorArgs(),
            args.getTopLevelTargets(),
            args.getBuildGraphs(),
            args.getCachingBuildEngineDelegate(),
            args.getDistBuildService(),
            args.getDistBuildClientStats(),
            args.getDistBuildLogStateTracker(),
            args.getScheduler(),
            args.getStatusPollIntervalMillis(),
            args.getRemoteBuildRuleCompletionNotifier(),
            consoleEventsDispatcher);
    this.postBuildPhase =
        new PostBuildPhase(
            args.getDistBuildService(),
            args.getDistBuildClientStats(),
            args.getDistBuildLogStateTracker(),
            args.getMaxTimeoutWaitingForLogsMillis(),
            args.getLogMaterializationEnabled());
    this.console = args.getBuilderExecutorArgs().getConsole();
  }

  /** Executes the tbuild and prints failures to the event bus. */
  public StampedeExecutionResult executeAndPrintFailuresToEventBus(
      ListeningExecutorService executorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      InvocationInfo invocationInfo,
      BuildMode buildMode,
      DistLocalBuildMode distLocalBuildMode,
      MinionRequirements minionRequirements,
      String repository,
      String tenantId,
      ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> ruleKeyCalculatorFuture)
      throws InterruptedException {
    Pair<StampedeId, ListenableFuture<Void>> stampedeIdAndPendingPrepFuture = null;
    try {
      stampedeIdAndPendingPrepFuture =
          Preconditions.checkNotNull(
              preBuildPhase.runPreDistBuildLocalStepsAsync(
                  executorService,
                  projectFilesystem,
                  fileHashCache,
                  eventBus,
                  invocationInfo.getBuildId(),
                  buildMode,
                  minionRequirements,
                  repository,
                  tenantId,
                  ruleKeyCalculatorFuture));
    } catch (DistBuildService.DistBuildRejectedException ex) {
      eventBus.post(
          new StampedeConsoleEvent(
              ConsoleEvent.createForMessageWithAnsiEscapeCodes(
                  Level.WARNING, console.getAnsi().asWarningText(ex.getMessage()))));
      return createFailedExecutionResult(
          DistributedExitCode.PREPARATION_STEP_FAILED, "preparation", ex, true);
    } catch (IOException | RuntimeException ex) {
      LOG.error(ex, "Distributed build preparation steps failed.");
      return createFailedExecutionResult(
          DistributedExitCode.PREPARATION_STEP_FAILED, "preparation", ex);
    }

    stampedeIdReference.set(stampedeIdAndPendingPrepFuture.getFirst());

    ListenableFuture<Void> pendingPrepFuture = stampedeIdAndPendingPrepFuture.getSecond();
    try {
      LOG.info("Waiting for pre-build preparation to finish.");
      pendingPrepFuture.get();
      LOG.info("Pre-build preparation finished.");
    } catch (InterruptedException ex) {
      pendingPrepFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw ex;
    } catch (ExecutionException ex) {
      LOG.error(ex, "Distributed build preparation async steps failed.");
      return createFailedExecutionResult(
          DistributedExitCode.PREPARATION_ASYNC_STEP_FAILED, "async preparation", ex);
    }

    BuildPhase.BuildResult buildResult = null;

    try {
      buildResult =
          buildPhase.runDistBuildAndUpdateConsoleStatus(
              executorService,
              Preconditions.checkNotNull(stampedeIdReference.get()),
              buildMode,
              distLocalBuildMode,
              invocationInfo,
              ruleKeyCalculatorFuture);
    } catch (IOException
        | ExecutionException
        | RuntimeException ex) { // Important: Don't swallow InterruptedException
      // Don't print an error here, because we might have failed due to local finishing first.
      LOG.warn(ex, "Distributed build step failed.");
      return createFailedExecutionResult(
          DistributedExitCode.DISTRIBUTED_BUILD_STEP_LOCAL_EXCEPTION, "build", ex);
    }

    // In the case of Fire-and-Forget mode do not run post-build steps.
    if (distLocalBuildMode.equals(DistLocalBuildMode.FIRE_AND_FORGET)) {
      LOG.info("Fire-and-forget mode enabled, exiting with default code.");
      eventBus.post(
          BuildEvent.distBuildFinished(startedEvent, DistributedExitCode.SUCCESSFUL.getCode()));
      return StampedeExecutionResult.of(DistributedExitCode.SUCCESSFUL);
    } else if (distLocalBuildMode.equals(DistLocalBuildMode.RULE_KEY_DIVERGENCE_CHECK)) {
      boolean ruleKeysMismatched = buildResult.getMismatchingBuildTargets().size() > 0;

      LOG.info("Rule key divergence check finished. Divergence detected: %s", ruleKeysMismatched);

      if (ruleKeysMismatched) {
        eventBus.post(
            ConsoleEvent.severe(
                "*** [%d] default rule keys mismatched between client and server. *** \nMismatching rule keys:",
                buildResult.getMismatchingBuildTargets().size()));
      } else {
        eventBus.post(
            ConsoleEvent.info("*** All rule keys matched between client and server ***:"));
      }

      // Outputs up to first 100 mismatching rules
      int rulesPrinted = 0;
      for (String mismatchingRule : buildResult.getMismatchingBuildTargets()) {
        if (rulesPrinted == MISMATCHING_RULE_PRINT_LIMIT) {
          eventBus.post(ConsoleEvent.severe(".... Further mismatches truncated"));
          break;
        }
        eventBus.post(ConsoleEvent.severe("MISMATCHING RULE: %s", mismatchingRule));
        rulesPrinted++;
      }

      DistributedExitCode exitCode =
          ruleKeysMismatched
              ? DistributedExitCode.RULE_KEY_CONSISTENCY_CHECK_FAILED
              : DistributedExitCode.SUCCESSFUL;
      eventBus.post(BuildEvent.distBuildFinished(startedEvent, exitCode.getCode()));
      return StampedeExecutionResult.of(exitCode);

    } else {
      // Send DistBuildFinished event if we reach this point without throwing.
      boolean buildSuccess =
          buildResult.getFinalBuildJob().getStatus().equals(BuildStatus.FINISHED_SUCCESSFULLY);
      eventBus.post(
          BuildEvent.distBuildFinished(
              startedEvent,
              buildSuccess
                  ? 0
                  : DistributedExitCode.DISTRIBUTED_BUILD_STEP_REMOTE_FAILURE.getCode()));

      // Note: always returns distributed exit code 0
      // TODO(alisdair,ruibm,shivanker): consider new exit code if failed to fetch finished stats
      return postBuildPhase.runPostDistBuildLocalSteps(
          executorService,
          buildResult.getBuildSlaveStatusList(),
          buildResult.getFinalBuildJob(),
          consoleEventsDispatcher);
    }
  }

  private StampedeExecutionResult createFailedExecutionResult(
      DistributedExitCode exitCode, String failureStage, Throwable ex) {
    return createFailedExecutionResult(exitCode, failureStage, ex, false);
  }

  private StampedeExecutionResult createFailedExecutionResult(
      DistributedExitCode exitCode, String failureStage, Throwable ex, boolean handleGracefully) {
    LOG.warn("Stampede failed. Cancel async job state computation if that's still going on.");
    asyncJobState.cancel(true);
    eventBus.post(BuildEvent.distBuildFinished(startedEvent, exitCode.getCode()));
    return StampedeExecutionResult.builder()
        .setExitCode(exitCode)
        .setException(ex)
        .setErrorStage(failureStage)
        .setHandleGracefully(handleGracefully)
        .build();
  }
}
