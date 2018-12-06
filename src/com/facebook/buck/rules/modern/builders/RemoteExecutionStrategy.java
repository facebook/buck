/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService.ExecutionResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.JobLimiter;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link BuildRuleStrategy} that uses a Remote Execution service for executing BuildRules. It
 * currently only supports ModernBuildRule and creates a remote Action With {@link
 * ModernBuildRuleRemoteExecutionHelper}.
 *
 * <p>See https://docs.google.com/document/d/1AaGk7fOPByEvpAbqeXIyE8HX_A3_axxNnvroblTZ_6s/preview
 * for a high-level description of the approach to remote execution.
 */
public class RemoteExecutionStrategy extends AbstractModernBuildRuleStrategy {
  private static final Logger LOG = Logger.get(RemoteExecutionStrategy.class);

  private final BuckEventBus eventBus;
  private final RemoteExecutionClients executionClients;
  private final ModernBuildRuleRemoteExecutionHelper mbrHelper;
  private final Path cellPathPrefix;

  private final ListeningExecutorService service;

  private final JobLimiter computeActionLimiter;
  private final JobLimiter pendingUploadsLimiter;
  private final JobLimiter executionLimiter;
  private final JobLimiter handleResultLimiter;

  RemoteExecutionStrategy(
      BuckEventBus eventBus,
      RemoteExecutionStrategyConfig strategyConfig,
      RemoteExecutionClients executionClients,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher) {
    this.executionClients = executionClients;
    this.service =
        MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("remote-exec", strategyConfig.getThreads()));
    this.computeActionLimiter = new JobLimiter(strategyConfig.getMaxConcurrentActionComputations());
    this.pendingUploadsLimiter = new JobLimiter(strategyConfig.getMaxConcurrentPendingUploads());
    this.executionLimiter = new JobLimiter(strategyConfig.getMaxConcurrentExecutions());
    this.handleResultLimiter = new JobLimiter(strategyConfig.getMaxConcurrentResultHandling());
    this.eventBus = eventBus;

    ImmutableSet<Optional<String>> cellNames =
        rootCell
            .getCellProvider()
            .getLoadedCells()
            .values()
            .stream()
            .map(Cell::getCanonicalName)
            .collect(ImmutableSet.toImmutableSet());

    this.cellPathPrefix =
        MorePaths.splitOnCommonPrefix(
                cellNames
                    .stream()
                    .map(name -> cellResolver.getCellPath(name).get())
                    .collect(ImmutableList.toImmutableList()))
            .get()
            .getFirst();

    this.mbrHelper =
        new ModernBuildRuleRemoteExecutionHelper(
            eventBus,
            this.executionClients.getProtocol(),
            ruleFinder,
            cellResolver,
            rootCell,
            cellNames,
            cellPathPrefix,
            fileHasher);
  }

  /** Creates a BuildRuleStrategy for a particular */
  static BuildRuleStrategy createRemoteExecutionStrategy(
      BuckEventBus eventBus,
      RemoteExecutionStrategyConfig strategyConfig,
      RemoteExecutionClients clients,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher) {
    return new RemoteExecutionStrategy(
        eventBus, strategyConfig, clients, ruleFinder, cellResolver, rootCell, fileHasher);
  }

  @Override
  public void close() throws IOException {
    executionClients.close();
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    Preconditions.checkState(rule instanceof ModernBuildRule);
    BuildTarget buildTarget = rule.getBuildTarget();

    RemoteExecutionActionEvent.sendScheduledEvent(eventBus, rule.getBuildTarget());

    ListenableFuture<RemoteExecutionActionInfo> actionInfoFuture =
        pendingUploadsLimiter.schedule(
            service, () -> computeActionAndUpload(rule, strategyContext));

    // guard should only be set once. The left value indicates that it has been cancelled and holds
    // the reason, a right value indicates that it has passed the point of no return and can no
    // longer be cancelled.
    AtomicReference<Either<Throwable, Object>> guard = new AtomicReference<>();

    ListenableFuture<Optional<BuildResult>> buildResult =
        Futures.transformAsync(
            actionInfoFuture,
            actionInfo ->
                handleActionInfo(
                    rule,
                    strategyContext,
                    buildTarget,
                    actionInfo,
                    () -> {
                      if (guard.compareAndSet(null, Either.ofRight(new Object()))) {
                        return null;
                      }
                      return Objects.requireNonNull(guard.get()).getLeft();
                    }),
            service);

    return new StrategyBuildResult() {
      @Override
      public void cancel(Throwable cause) {
        guard.compareAndSet(null, Either.ofLeft(cause));
      }

      @Override
      public boolean cancelIfNotStarted(Throwable reason) {
        cancel(reason);
        // cancel() will have set the guard value if it weren't already set.
        return Objects.requireNonNull(guard.get()).isLeft();
      }

      @Override
      public ListenableFuture<Optional<BuildResult>> getBuildResult() {
        return buildResult;
      }
    };
  }

  private ListenableFuture<RemoteExecutionActionInfo> computeActionAndUpload(
      BuildRule rule, BuildStrategyContext strategyContext) {
    ListenableFuture<RemoteExecutionActionInfo> actionInfoFuture =
        computeActionLimiter.schedule(
            service,
            () -> Futures.immediateFuture(getRemoteExecutionActionInfo(rule, strategyContext)));
    return Futures.transformAsync(
        actionInfoFuture, actionInfo -> uploadInputs(rule.getBuildTarget(), actionInfo));
  }

  private ListenableFuture<RemoteExecutionActionInfo> uploadInputs(
      BuildTarget buildTarget, RemoteExecutionActionInfo actionInfo) throws IOException {
    Objects.requireNonNull(actionInfo);
    Digest actionDigest = actionInfo.getActionDigest();
    ImmutableMap<Digest, UploadDataSupplier> requiredData = actionInfo.getRequiredData();
    Scope uploadingInputsScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.UPLOADING_INPUTS, buildTarget, Optional.of(actionDigest));
    ListenableFuture<Void> inputsUploadedFuture =
        executionClients.getContentAddressedStorage().addMissing(requiredData);
    return Futures.transform(
        inputsUploadedFuture,
        ignored -> {
          uploadingInputsScope.close();
          // The actionInfo may be very large, so explicitly clear out the unneeded parts.
          // actionInfo.getRequiredData() in particular may be very, very large and is unneeded once
          // uploading has completed.
          return actionInfo.withRequiredData(ImmutableMap.of());
        });
  }

  private ListenableFuture<Optional<BuildResult>> handleActionInfo(
      BuildRule rule,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      RemoteExecutionActionInfo actionInfo,
      Callable<Throwable> tryStart)
      throws IOException {
    Objects.requireNonNull(actionInfo);
    // The actionInfo may be very large, so explicitly capture just the parts that we need and clear
    // it (to hopefully catch future bad uses). actionInfo.getRequiredData() in particular may be
    // very, very large.
    Digest actionDigest = actionInfo.getActionDigest();
    Iterable<? extends Path> actionOutputs = actionInfo.getOutputs();
    ImmutableMap<Digest, UploadDataSupplier> requiredData = actionInfo.getRequiredData();
    Scope uploadingInputsScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.UPLOADING_INPUTS, buildTarget, Optional.of(actionDigest));

    ListenableFuture<Void> inputsUploadedFuture =
        executionClients.getContentAddressedStorage().addMissing(requiredData);

    return Futures.transformAsync(
        inputsUploadedFuture,
        ignored -> {
          uploadingInputsScope.close();
          return executeNowThatInputsAreReady(
              rule.getProjectFilesystem(),
              strategyContext,
              buildTarget,
              tryStart,
              actionDigest,
              actionOutputs);
        },
        service);
  }

  private RemoteExecutionActionInfo getRemoteExecutionActionInfo(
      BuildRule rule, BuildStrategyContext strategyContext) throws IOException {
    try (Scope ignored = strategyContext.buildRuleScope()) {
      RemoteExecutionActionInfo actionInfo;

      try (Scope ignored1 =
          RemoteExecutionActionEvent.sendEvent(
              eventBus, State.COMPUTING_ACTION, rule.getBuildTarget(), Optional.empty())) {
        actionInfo = mbrHelper.prepareRemoteExecution((ModernBuildRule<?>) rule);
      }
      return actionInfo;
    }
  }

  private ListenableFuture<Optional<BuildResult>> executeNowThatInputsAreReady(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      Callable<Throwable> tryStart,
      Digest actionDigest,
      Iterable<? extends Path> actionOutputs) {
    AtomicReference<Throwable> cancelled = new AtomicReference<>(null);
    ListenableFuture<ExecutionResult> executionResult =
        executionLimiter.schedule(
            service,
            () -> {
              cancelled.set(tryStart.call());
              boolean isCancelled = cancelled.get() != null;
              if (isCancelled) {
                RemoteExecutionActionEvent.sendTerminalEvent(
                    eventBus, State.ACTION_CANCELLED, buildTarget, Optional.of(actionDigest));
                return Futures.immediateFuture(null);
              }
              Scope executingScope =
                  RemoteExecutionActionEvent.sendEvent(
                      eventBus, State.EXECUTING, buildTarget, Optional.of(actionDigest));
              return Futures.transform(
                  executionClients.getRemoteExecutionService().execute(actionDigest),
                  result -> {
                    executingScope.close();
                    return result;
                  });
            });

    return Futures.transformAsync(
        executionResult,
        result -> {
          if (cancelled.get() != null) {
            return Futures.immediateFuture(
                Optional.of(strategyContext.createCancelledResult(cancelled.get())));
          }
          return handleResultLimiter.schedule(
              service,
              () ->
                  handleExecutionResult(
                      filesystem,
                      strategyContext,
                      buildTarget,
                      result,
                      actionDigest,
                      actionOutputs));
        },
        service);
  }

  private ListenableFuture<Optional<BuildResult>> handleExecutionResult(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      ExecutionResult result,
      Digest actionDigest,
      Iterable<? extends Path> actionOutputs)
      throws IOException, StepFailedException {
    if (result.getExitCode() != 0) {
      LOG.error(
          "Failed to build target [%s] with exit code [%d]. stderr: %s",
          buildTarget.getFullyQualifiedName(),
          result.getExitCode(),
          result.getStderr().orElse("<empty>"));
      RemoteExecutionActionEvent.sendTerminalEvent(
          eventBus, State.ACTION_FAILED, buildTarget, Optional.of(actionDigest));
      throw StepFailedException.createForFailingStepWithExitCode(
          new AbstractExecutionStep("remote_execution") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              throw new RuntimeException();
            }
          },
          strategyContext.getExecutionContext(),
          StepExecutionResult.of(result.getExitCode(), result.getStderr()));
    }

    Scope materializationScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.MATERIALIZING_OUTPUTS, buildTarget, Optional.of(actionDigest));

    try (Scope ignored1 =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.DELETING_STALE_OUTPUTS, buildTarget, Optional.of(actionDigest))) {
      for (Path path : actionOutputs) {
        MostFiles.deleteRecursivelyIfExists(cellPathPrefix.resolve(path));
      }
    }

    ListenableFuture<Void> materializationFuture =
        executionClients
            .getContentAddressedStorage()
            .materializeOutputs(
                result.getOutputDirectories(), result.getOutputFiles(), cellPathPrefix);

    return Futures.transform(
        materializationFuture,
        ignored -> {
          materializationScope.close();
          RemoteExecutionActionEvent.sendTerminalEvent(
              eventBus, State.ACTION_SUCCEEDED, buildTarget, Optional.of(actionDigest));
          actionOutputs.forEach(
              output ->
                  strategyContext
                      .getBuildableContext()
                      .recordArtifact(filesystem.relativize(cellPathPrefix.resolve(output))));
          return Optional.of(strategyContext.createBuildResult(BuildRuleSuccessType.BUILT_LOCALLY));
        },
        MoreExecutors.directExecutor());
  }
}
