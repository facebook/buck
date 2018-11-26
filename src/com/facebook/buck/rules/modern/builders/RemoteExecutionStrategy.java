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
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService.ExecutionResult;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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
  private final Optional<ListeningExecutorService> executorService;
  private final Path cellPathPrefix;

  RemoteExecutionStrategy(
      BuckEventBus eventBus,
      RemoteExecutionClients executionClients,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      Optional<ListeningExecutorService> executorService)
      throws IOException {
    this.executionClients = executionClients;
    this.executorService = executorService;
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
      Optional<ListeningExecutorService> remoteExecutorService,
      RemoteExecutionClients clients,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher)
      throws IOException {
    return new RemoteExecutionStrategy(
        eventBus, clients, ruleFinder, cellResolver, rootCell, fileHasher, remoteExecutorService);
  }

  @Override
  public void close() throws IOException {
    executionClients.close();
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    Preconditions.checkState(rule instanceof ModernBuildRule);
    ListeningExecutorService service = executorService.orElse(strategyContext.getExecutorService());
    BuildTarget buildTarget = rule.getBuildTarget();

    ListenableFuture<RemoteExecutionActionInfo> actionInfoFuture =
        service.submit(() -> getRemoteExecutionActionInfo(rule, strategyContext, buildTarget));

    ListenableFuture<Optional<BuildResult>> buildResult =
        Futures.transformAsync(
            actionInfoFuture,
            actionInfo -> {
              Objects.requireNonNull(actionInfo);
              Scope uploadingInputsScope =
                  RemoteExecutionActionEvent.sendEvent(
                      eventBus,
                      State.UPLOADING_INPUTS,
                      buildTarget,
                      Optional.of(actionInfo.getActionDigest()));
              ListenableFuture<Void> inputsUploadedFuture =
                  executionClients
                      .getContentAddressedStorage()
                      .addMissing(actionInfo.getRequiredData());

              return Futures.transformAsync(
                  inputsUploadedFuture,
                  ignored -> {
                    uploadingInputsScope.close();
                    return executeNowThatInputsAreReady(
                        rule.getProjectFilesystem(),
                        strategyContext,
                        buildTarget,
                        actionInfo,
                        service);
                  },
                  service);
            },
            service);

    return StrategyBuildResult.nonCancellable(buildResult);
  }

  private RemoteExecutionActionInfo getRemoteExecutionActionInfo(
      BuildRule rule, BuildStrategyContext strategyContext, BuildTarget buildTarget)
      throws IOException {
    try (Scope ignored = strategyContext.buildRuleScope()) {
      RemoteExecutionActionInfo actionInfo;

      try (Scope ignored1 =
          RemoteExecutionActionEvent.sendEvent(
              eventBus, State.COMPUTING_ACTION, rule.getBuildTarget(), Optional.empty())) {
        actionInfo =
            mbrHelper.prepareRemoteExecution(
                (ModernBuildRule<?>) rule, strategyContext.getBuildRuleBuildContext());
      }

      try (Scope ignored1 =
          RemoteExecutionActionEvent.sendEvent(
              eventBus,
              State.DELETING_STALE_OUTPUTS,
              buildTarget,
              Optional.of(actionInfo.getActionDigest()))) {
        for (Path path : actionInfo.getOutputs()) {
          MostFiles.deleteRecursivelyIfExists(cellPathPrefix.resolve(path));
        }
      }
      return actionInfo;
    }
  }

  private ListenableFuture<Optional<BuildResult>> executeNowThatInputsAreReady(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      RemoteExecutionActionInfo actionInfo,
      ListeningExecutorService service)
      throws IOException, InterruptedException {
    Scope executingScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.EXECUTING, buildTarget, Optional.of(actionInfo.getActionDigest()));
    ListenableFuture<ExecutionResult> executionResult =
        executionClients.getRemoteExecutionService().execute(actionInfo.getActionDigest());
    return Futures.transformAsync(
        executionResult,
        result -> {
          executingScope.close();
          return handleExecutionResult(
              filesystem, strategyContext, buildTarget, actionInfo, result);
        },
        service);
  }

  private ListenableFuture<Optional<BuildResult>> handleExecutionResult(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      RemoteExecutionActionInfo actionInfo,
      ExecutionResult result)
      throws IOException, StepFailedException {
    if (result.getExitCode() != 0) {
      LOG.error(
          "Failed to build target [%s] with exit code [%d]. stderr: %s",
          buildTarget.getFullyQualifiedName(),
          result.getExitCode(),
          result.getStderr().orElse("<empty>"));
      RemoteExecutionActionEvent.sendTerminalEvent(
          eventBus, State.ACTION_FAILED, buildTarget, Optional.of(actionInfo.getActionDigest()));
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
            eventBus,
            State.MATERIALIZING_OUTPUTS,
            buildTarget,
            Optional.of(actionInfo.getActionDigest()));

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
              eventBus,
              State.ACTION_SUCCEEDED,
              buildTarget,
              Optional.of(actionInfo.getActionDigest()));
          actionInfo
              .getOutputs()
              .forEach(
                  output ->
                      strategyContext
                          .getBuildableContext()
                          .recordArtifact(filesystem.relativize(cellPathPrefix.resolve(output))));
          return Optional.of(strategyContext.createBuildResult(BuildRuleSuccessType.BUILT_LOCALLY));
        },
        MoreExecutors.directExecutor());
  }
}
