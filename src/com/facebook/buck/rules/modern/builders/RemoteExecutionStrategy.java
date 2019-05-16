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

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionHandle;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionResult;
import com.facebook.buck.remoteexecution.WorkerRequirementsProvider;
import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.event.RemoteExecutionSessionEvent;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.util.OutputsMaterializer.FilesystemFileMaterializer;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.JobLimiter;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

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
  private static final int WORKER_REQUIREMENTS_PROVIDER_MAX_CACHE_SIZE = 1000;

  private final BuckEventBus eventBus;
  private final RemoteExecutionClients executionClients;
  private final RemoteExecutionHelper mbrHelper;

  private final ListeningExecutorService service;

  private final JobLimiter computeActionLimiter;
  private final JobLimiter pendingUploadsLimiter;
  private final JobLimiter executionLimiter;
  private final JobLimiter handleResultLimiter;
  private final OptionalLong maxInputSizeBytes;
  private final WorkerRequirementsProvider requirementsProvider;
  private final MetadataProvider metadataProvider;
  private final RemoteExecutionSessionEvent.Started remoteExecutionSessionStartedEvent;

  RemoteExecutionStrategy(
      BuckEventBus eventBus,
      RemoteExecutionStrategyConfig strategyConfig,
      RemoteExecutionClients executionClients,
      MetadataProvider metadataProvider,
      RemoteExecutionHelper mbrHelper,
      ListeningExecutorService service) {
    this.executionClients = executionClients;
    this.service = service;
    this.computeActionLimiter = new JobLimiter(strategyConfig.getMaxConcurrentActionComputations());
    this.pendingUploadsLimiter = new JobLimiter(strategyConfig.getMaxConcurrentPendingUploads());
    this.executionLimiter = new JobLimiter(strategyConfig.getMaxConcurrentExecutions());
    this.handleResultLimiter = new JobLimiter(strategyConfig.getMaxConcurrentResultHandling());
    this.maxInputSizeBytes = strategyConfig.maxInputSizeBytes();
    this.eventBus = eventBus;
    this.metadataProvider = metadataProvider;
    this.mbrHelper = mbrHelper;
    this.requirementsProvider =
        new WorkerRequirementsProvider(
            strategyConfig.getWorkerRequirementsFilename(),
            strategyConfig.tryLargerWorkerOnOom(),
            WORKER_REQUIREMENTS_PROVIDER_MAX_CACHE_SIZE);
    this.remoteExecutionSessionStartedEvent = RemoteExecutionSessionEvent.started();
    this.eventBus.post(remoteExecutionSessionStartedEvent);
  }

  /** Creates a BuildRuleStrategy for a particular */
  static BuildRuleStrategy createRemoteExecutionStrategy(
      BuckEventBus eventBus,
      RemoteExecutionStrategyConfig strategyConfig,
      RemoteExecutionClients clients,
      SourcePathRuleFinder ruleFinder,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      MetadataProvider metadataProvider) {
    BuildRuleStrategy strategy =
        new RemoteExecutionStrategy(
            eventBus,
            strategyConfig,
            clients,
            metadataProvider,
            new ModernBuildRuleRemoteExecutionHelper(
                eventBus, clients.getProtocol(), ruleFinder, rootCell, fileHasher),
            MoreExecutors.listeningDecorator(
                MostExecutors.newMultiThreadExecutor("remote-exec", strategyConfig.getThreads())));
    if (strategyConfig.isLocalFallbackEnabled()) {
      strategy = new LocalFallbackStrategy(strategy, eventBus);
    }

    return strategy;
  }

  @Override
  public boolean canBuild(BuildRule instance) {
    if (instance instanceof ModernBuildRule) {
      return super.canBuild(instance)
          && mbrHelper.supportsRemoteExecution((ModernBuildRule<?>) instance);
    }
    return false;
  }

  @Override
  public void close() throws IOException {
    executionClients.close();
    eventBus.post(RemoteExecutionSessionEvent.finished(remoteExecutionSessionStartedEvent));
  }

  @Override
  public StrategyBuildResult build(BuildRule rule, BuildStrategyContext strategyContext) {
    Preconditions.checkState(rule instanceof ModernBuildRule);
    BuildTarget buildTarget = rule.getBuildTarget();

    RemoteExecutionActionEvent.sendScheduledEvent(eventBus, rule.getBuildTarget());

    ListenableFuture<RemoteExecutionActionInfo> actionInfoFuture =
        pendingUploadsLimiter.schedule(
            service, () -> computeActionAndUpload(rule, strategyContext));

    GuardContext guardContext = new GuardContext();
    ListenableFuture<Optional<BuildResult>> buildResult =
        Futures.transformAsync(
            actionInfoFuture,
            actionInfo ->
                handleActionInfo(rule, strategyContext, buildTarget, actionInfo, guardContext),
            service);

    return new StrategyBuildResult() {
      @Override
      public void cancel(Throwable cause) {
        guardContext.cancel(cause);
      }

      @Override
      public boolean cancelIfNotComplete(Throwable reason) {
        cancel(reason);
        return guardContext.isCancelled();
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
        actionInfoFuture,
        actionInfo -> uploadInputs(rule.getBuildTarget(), actionInfo),
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<RemoteExecutionActionInfo> uploadInputs(
      BuildTarget buildTarget, RemoteExecutionActionInfo actionInfo) throws Exception {
    Objects.requireNonNull(actionInfo);
    if (maxInputSizeBytes.isPresent()
        && maxInputSizeBytes.getAsLong() < actionInfo.getTotalInputSize()) {
      throw new RuntimeException(
          "Max file size exceeded for Remote Execution, action contains: "
              + actionInfo.getTotalInputSize()
              + " bytes, max allowed: "
              + maxInputSizeBytes.getAsLong());
    }
    Digest actionDigest = actionInfo.getActionDigest();
    Scope uploadingInputsScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.UPLOADING_INPUTS, buildTarget, Optional.of(actionDigest));
    ListenableFuture<Void> inputsUploadedFuture =
        executionClients.getContentAddressedStorage().addMissing(actionInfo.getRequiredData());
    return Futures.transform(
        inputsUploadedFuture,
        ignored -> {
          uploadingInputsScope.close();
          // The actionInfo may be very large, so explicitly clear out the unneeded parts.
          // actionInfo.getRequiredData() in particular may be very, very large and is unneeded once
          // uploading has completed.
          return actionInfo.withRequiredData(ImmutableList.of());
        },
        MoreExecutors.directExecutor());
  }

  private ListenableFuture<Optional<BuildResult>> handleActionInfo(
      BuildRule rule,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      RemoteExecutionActionInfo actionInfo,
      GuardContext guardContext)
      throws IOException {
    Objects.requireNonNull(actionInfo);
    // The actionInfo may be very large, so explicitly capture just the parts that we need and clear
    // it (to hopefully catch future bad uses). actionInfo.getRequiredData() in particular may be
    // very, very large.
    Digest actionDigest = actionInfo.getActionDigest();
    Iterable<? extends Path> actionOutputs = actionInfo.getOutputs();
    Scope uploadingInputsScope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.UPLOADING_INPUTS, buildTarget, Optional.of(actionDigest));

    ListenableFuture<Void> inputsUploadedFuture =
        executionClients.getContentAddressedStorage().addMissing(actionInfo.getRequiredData());

    return Futures.transformAsync(
        inputsUploadedFuture,
        ignored -> {
          uploadingInputsScope.close();
          return executeNowThatInputsAreReady(
              rule.getProjectFilesystem(),
              strategyContext,
              rule,
              guardContext,
              actionDigest,
              actionOutputs,
              rule.getFullyQualifiedName());
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
        actionInfo =
            mbrHelper.prepareRemoteExecution(
                (ModernBuildRule<?>) rule,
                digest -> !executionClients.getContentAddressedStorage().containsDigest(digest),
                requirementsProvider.resolveRequirements(rule.getBuildTarget()));
      }
      return actionInfo;
    }
  }

  private ListenableFuture<Optional<BuildResult>> executeNowThatInputsAreReady(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildRule buildRule,
      GuardContext guardContext,
      Digest actionDigest,
      Iterable<? extends Path> actionOutputs,
      String ruleName) {
    AtomicReference<Throwable> cancelled = new AtomicReference<>(null);
    MetadataProvider metadataProvider =
        MetadataProviderFactory.wrapForRuleWithWorkerRequirements(
            this.metadataProvider,
            () -> requirementsProvider.resolveRequirements(buildRule.getBuildTarget()));
    ListenableFuture<ExecutionResult> executionResult =
        executionLimiter.schedule(
            service,
            () -> {
              if (guardContext.isCancelled()) {
                cancelled.set(guardContext.getCancelReason());
                RemoteExecutionActionEvent.sendTerminalEvent(
                    eventBus,
                    State.ACTION_CANCELLED,
                    buildRule.getBuildTarget(),
                    Optional.of(actionDigest),
                    Optional.empty());
                return Futures.immediateFuture(null);
              }
              Scope executingScope =
                  RemoteExecutionActionEvent.sendEvent(
                      eventBus,
                      State.EXECUTING,
                      buildRule.getBuildTarget(),
                      Optional.of(actionDigest));
              ExecutionHandle executionHandle =
                  executionClients
                      .getRemoteExecutionService()
                      .execute(actionDigest, ruleName, metadataProvider);

              guardContext.onCancellation(reason -> executionHandle.cancel());
              Futures.addCallback(
                  executionHandle.getExecutionStarted(),
                  new FutureCallback<ExecuteOperationMetadata>() {
                    @Override
                    public void onSuccess(@Nullable ExecuteOperationMetadata result) {
                      guardContext.tryStart();
                    }

                    @Override
                    public void onFailure(Throwable t) {}
                  },
                  MoreExecutors.directExecutor());
              return Futures.transform(
                  executionHandle.getResult(),
                  result -> {
                    executingScope.close();
                    // Try Start so that if Executing Started was never sent, we can don't block
                    // cancellation till the result is ready.
                    guardContext.tryStart();
                    if (guardContext.isCancelled()) {
                      cancelled.set(guardContext.getCancelReason());
                      RemoteExecutionActionEvent.sendTerminalEvent(
                          eventBus,
                          State.ACTION_CANCELLED,
                          buildRule.getBuildTarget(),
                          Optional.of(actionDigest),
                          Optional.empty());
                      return null;
                    }
                    return result;
                  },
                  MoreExecutors.directExecutor());
            });

    return sendFailedEventOnException(
        Futures.transformAsync(
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
                          buildRule.getBuildTarget(),
                          result,
                          actionDigest,
                          actionOutputs,
                          metadataProvider));
            },
            service),
        buildRule.getBuildTarget(),
        actionDigest);
  }

  private ListenableFuture<Optional<BuildResult>> sendFailedEventOnException(
      ListenableFuture<Optional<BuildResult>> futureToBeWrapped,
      BuildTarget buildTarget,
      Digest actionDigest) {
    futureToBeWrapped.addListener(
        () -> {
          Optional<Throwable> exception = Optional.empty();
          try {
            futureToBeWrapped.get();
          } catch (InterruptedException e) {
            exception = Optional.of(e);
          } catch (ExecutionException e) {
            exception = Optional.of(e.getCause());
          }

          exception.ifPresent(
              throwable ->
                  RemoteExecutionActionEvent.sendTerminalEvent(
                      eventBus,
                      throwable instanceof InterruptedException
                          ? State.ACTION_CANCELLED
                          : State.ACTION_FAILED,
                      buildTarget,
                      Optional.of(actionDigest),
                      Optional.empty()));
        },
        MoreExecutors.directExecutor());

    return futureToBeWrapped;
  }

  private ListenableFuture<Optional<BuildResult>> handleExecutionResult(
      ProjectFilesystem filesystem,
      BuildStrategyContext strategyContext,
      BuildTarget buildTarget,
      ExecutionResult result,
      Digest actionDigest,
      Iterable<? extends Path> actionOutputs,
      MetadataProvider metadataProvider)
      throws IOException, StepFailedException {
    LOG.debug(
        "[RE] Built target [%s] with exit code [%d]. Action: [%s]. ActionResult: [%s]",
        buildTarget.getFullyQualifiedName(),
        result.getExitCode(),
        actionDigest,
        result.getActionResultDigest());
    if (result.getExitCode() != 0) {
      LOG.info(
          "[RE] Failed to build target [%s] with exit code [%d]. "
              + "stdout: [%s] stderr: [%s] metadata: [%s] action: [%s]",
          buildTarget.getFullyQualifiedName(),
          result.getExitCode(),
          result.getStdout().orElse("<empty>"),
          result.getStderr().orElse("<empty>"),
          metadataProvider.toString(),
          actionDigest);
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
        MostFiles.deleteRecursivelyIfExists(mbrHelper.getCellPathPrefix().resolve(path));
      }
    }

    ListenableFuture<Void> materializationFuture =
        executionClients
            .getContentAddressedStorage()
            .materializeOutputs(
                result.getOutputDirectories(),
                result.getOutputFiles(),
                new FilesystemFileMaterializer(mbrHelper.getCellPathPrefix()));

    return Futures.transform(
        materializationFuture,
        ignored -> {
          materializationScope.close();
          RemoteExecutionActionEvent.sendTerminalEvent(
              eventBus,
              State.ACTION_SUCCEEDED,
              buildTarget,
              Optional.of(actionDigest),
              Optional.of(result.getActionMetadata()));
          actionOutputs.forEach(
              output ->
                  strategyContext
                      .getBuildableContext()
                      .recordArtifact(
                          filesystem.relativize(mbrHelper.getCellPathPrefix().resolve(output))));
          return Optional.of(
              strategyContext.createBuildResult(
                  BuildRuleSuccessType.BUILT_LOCALLY, Optional.of("built remotely")));
        },
        MoreExecutors.directExecutor());
  }

  private static class GuardContext {
    // guard should only be set once. The left value indicates that it has been cancelled and holds
    // the reason, a right value indicates that it has passed the point of no return and can no
    // longer be cancelled.
    AtomicReference<Either<Throwable, Object>> guard = new AtomicReference<>();
    ConcurrentLinkedQueue<Consumer<Throwable>> callbackQueue = new ConcurrentLinkedQueue<>();

    public boolean isCancelled() {
      return guard.get() != null && guard.get().isLeft();
    }

    public Throwable getCancelReason() {
      Verify.verify(isCancelled());
      return guard.get().getLeft();
    }

    public void cancel(Throwable reason) {
      guard.compareAndSet(null, Either.ofLeft(reason));
      if (isCancelled()) {
        processCallbackQueue();
      }
    }

    public boolean tryStart() {
      return guard.compareAndSet(null, Either.ofRight(new Object()));
    }

    public void onCancellation(Consumer<Throwable> cancelCallback) {
      callbackQueue.add(cancelCallback);
      if (isCancelled()) {
        processCallbackQueue();
      }
    }

    private void processCallbackQueue() {
      Throwable cancelReason = getCancelReason();
      while (!callbackQueue.isEmpty()) {
        Consumer<Throwable> callback = callbackQueue.poll();
        if (callback == null) {
          break;
        }
        try {
          callback.accept(cancelReason);
        } catch (Exception e) {
          LOG.warn(e, "Unexpected exception while processing cancellation callbacks.");
        }
      }
    }
  }
}
