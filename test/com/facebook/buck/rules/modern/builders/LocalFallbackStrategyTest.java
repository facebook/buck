/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.proto.ExecutedActionInfo;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.remoteexecution.proto.WorkerInfo;
import com.facebook.buck.remoteexecution.util.MultiThreadedBlobUploader;
import com.facebook.buck.rules.modern.builders.LocalFallbackStrategy.FallbackStrategyBuildResult;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TestExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.BoolValue;
import io.grpc.Status;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LocalFallbackStrategyTest {
  private static final String RULE_NAME = "//topspin:rule";

  private RemoteExecutionStrategy.RemoteExecutionStrategyBuildResult strategyBuildResult;
  private BuildStrategyContext buildStrategyContext;
  private ListeningExecutorService directExecutor;
  private BuckEventBus eventBus;
  private RemoteRuleContext ruleContext;
  private ExecutionContext executionContext;

  @Before
  public void setUp() {
    strategyBuildResult =
        EasyMock.createMock(RemoteExecutionStrategy.RemoteExecutionStrategyBuildResult.class);
    buildStrategyContext = EasyMock.createMock(BuildStrategyContext.class);
    directExecutor = MoreExecutors.newDirectExecutorService();
    eventBus = EasyMock.createNiceMock(BuckEventBus.class);
    ruleContext = new RemoteRuleContext(eventBus, buildRule(RULE_NAME));
    executionContext = TestExecutionContext.newInstance();
  }

  @After
  public void tearDown() {}

  @Test
  public void testRemoteSuccess() throws ExecutionException, InterruptedException {
    BuildResult buildResult = successBuildResult("//finished:successfully");
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(buildResult)))
        .times(2);
    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertEquals(buildResult, fallbackStrategyBuildResult.getBuildResult().get().get());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteInterruptedException() throws ExecutionException, InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(
            Futures.immediateFailedFuture(new InterruptedException("This did not go well...")))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    EasyMock.expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteGrpcException() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(EasyMock.capture(eventCapture));
    EasyMock.expectLastCall().times(6);
    EasyMock.replay(eventBus);

    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(Status.DEADLINE_EXCEEDED.asRuntimeException()))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    EasyMock.expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    ruleContext.enterState(State.UPLOADING_ACTION, Optional.empty());
    ruleContext.enterState(State.EXECUTING, Optional.empty());
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());
    EasyMock.verify(strategyBuildResult, buildStrategyContext);

    List<LocalFallbackEvent> events = eventCapture.getValues();
    Assert.assertEquals(6, events.size());
    Assert.assertTrue(events.get(4) instanceof LocalFallbackEvent.Started);
    Assert.assertTrue(events.get(5) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(5);
    Assert.assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.DEADLINE_EXCEEDED);
    Assert.assertEquals(finishedEvent.getLastNonTerminalState(), State.EXECUTING);
    Assert.assertEquals(finishedEvent.getExitCode(), OptionalInt.empty());
  }

  @Test
  public void testExitCodeAndFallback()
      throws ExecutionException, InterruptedException, IOException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(EasyMock.capture(eventCapture));
    EasyMock.expectLastCall().times(2);
    EasyMock.replay(eventBus);
    String mockWorker = "mock_worker";
    RemoteExecutionMetadata remoteExecutionMetadata =
        RemoteExecutionMetadata.newBuilder()
            .setWorkerInfo(WorkerInfo.newBuilder().setHostname(mockWorker).build())
            .setExecutedActionInfo(
                ExecutedActionInfo.newBuilder()
                    .setIsFallbackEnabledForCompletedAction(
                        BoolValue.newBuilder().setValue(true).build())
                    .build())
            .build();

    StepFailedException exc =
        StepFailedException.createForFailingStepWithExitCode(
            new AbstractExecutionStep("remote_execution") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                throw new RuntimeException();
              }
            },
            executionContext,
            StepExecutionResult.builder().setExitCode(1).setStderr("").build(),
            remoteExecutionMetadata);

    // Just here to test if this is serializable by jackson, as we do Log.warn this.
    new ObjectMapper().writeValueAsString(exc);

    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exc))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    EasyMock.expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, false);
    Assert.assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());
    EasyMock.verify(strategyBuildResult, buildStrategyContext);

    List<LocalFallbackEvent> events = eventCapture.getValues();
    Assert.assertTrue(events.get(0) instanceof LocalFallbackEvent.Started);
    Assert.assertTrue(events.get(1) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(1);
    Assert.assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.OK);
    Assert.assertEquals(finishedEvent.getExitCode(), OptionalInt.of(1));
    Assert.assertEquals(
        finishedEvent.getRemoteExecutionMetadata().get().getWorkerInfo().getHostname(), mockWorker);
  }

  @Test
  public void testCancellation() throws ExecutionException, InterruptedException {
    Exception cause = new RuntimeException();
    BuildResult cancelledResult = cancelledBuildResult(RULE_NAME, cause);
    SettableFuture<Optional<BuildResult>> strategyResult = SettableFuture.create();
    EasyMock.expect(strategyBuildResult.getBuildResult()).andReturn(strategyResult).anyTimes();
    strategyBuildResult.cancel(cause);
    EasyMock.expect(buildStrategyContext.createCancelledResult(cause)).andReturn(cancelledResult);
    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    fallbackStrategyBuildResult.cancel(cause);
    Assert.assertEquals(cancelledResult, fallbackStrategyBuildResult.getBuildResult().get().get());
    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionError() throws ExecutionException, InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//super:cool"))))
        .times(2);
    BuildResult localResult = successBuildResult("//hurrah:weeeee");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionErrorFallbackDisabled()
      throws ExecutionException, InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//super:cool"))))
        .times(2);

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, false, false, true);
    Assert.assertEquals(
        BuildRuleStatus.FAIL, fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionCorruptArtifactError()
      throws ExecutionException, InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(
            Futures.immediateFuture(
                Optional.of(
                    failedBuildResultWithException(
                        "//super:cool",
                        new MultiThreadedBlobUploader.CorruptArtifactException(
                            "uhoh", "description")))))
        .times(2);

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, true, true);
    Assert.assertEquals(
        BuildRuleStatus.FAIL, fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionExceptionFallbackDisabled()
      throws ExecutionException, InterruptedException {
    Exception exception = new Exception("local failed miserably.");
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exception))
        .times(2);
    EasyMock.expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);
    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, false, false, true);
    Assert.assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      Assert.fail("Should've thrown...");
    } catch (ExecutionException e) {
      Assert.assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionExceptionFallbackDisabledForBuildError() throws InterruptedException {
    Exception exception =
        StepFailedException.createForFailingStepWithExitCode(
            new AbstractExecutionStep("remote_execution") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                throw new RuntimeException();
              }
            },
            executionContext,
            StepExecutionResult.builder().setExitCode(1).setStderr("").build());
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exception))
        .times(2);
    EasyMock.expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);
    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, false);
    Assert.assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      Assert.fail("Should've thrown...");
    } catch (ExecutionException e) {
      Assert.assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testLocalError() throws ExecutionException, InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//will/fail:remotely"))))
        .times(2);
    BuildResult localResult = failedBuildResult("//will/fail:locally");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testLocalException() throws InterruptedException {
    EasyMock.expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//will/fail:remotely"))))
        .times(2);
    Exception exception = new Exception("local failed miserably.");
    EasyMock.expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFailedFuture(exception))
        .once();
    EasyMock.expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    EasyMock.replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    Assert.assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      Assert.fail("Should've thrown...");
    } catch (ExecutionException e) {
      Assert.assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    EasyMock.verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testEventsAreSent() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(EasyMock.capture(eventCapture));
    EasyMock.expectLastCall().times(2);
    EasyMock.replay(eventBus);

    testRemoteActionError();

    List<LocalFallbackEvent> events = eventCapture.getValues();
    Assert.assertTrue(events.get(0) instanceof LocalFallbackEvent.Started);
    Assert.assertTrue(events.get(1) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(1);
    Assert.assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.OK);
  }

  @Test
  public void testEventBusLogForFallbackDisabled() throws ExecutionException, InterruptedException {
    Capture<AbstractBuckEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(EasyMock.capture(eventCapture));
    EasyMock.expectLastCall().times(3);
    EasyMock.replay(eventBus);

    testRemoteActionExceptionFallbackDisabledForBuildError();

    List<AbstractBuckEvent> events = eventCapture.getValues();
    Assert.assertTrue(events.get(0) instanceof LocalFallbackEvent.Started);
    Assert.assertTrue(events.get(1) instanceof ConsoleEvent);
    Assert.assertTrue(events.get(2) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(2);
    Assert.assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.OK);
  }

  @Test
  public void testInterruptedState() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(EasyMock.capture(eventCapture));
    EasyMock.expectLastCall().times(2);
    EasyMock.replay(eventBus);

    testRemoteInterruptedException();

    List<LocalFallbackEvent> events = eventCapture.getValues();
    LocalFallbackEvent.Finished event = (LocalFallbackEvent.Finished) events.get(1);
    Assert.assertEquals(LocalFallbackEvent.Result.INTERRUPTED, event.getRemoteResult());
    Assert.assertEquals(LocalFallbackEvent.Result.SUCCESS, event.getLocalResult());
  }

  private static BuildRule buildRule(String name) {
    return new FakeBuildRule(BuildTargetFactory.newInstance(name));
  }

  private static BuildResult failedBuildResult(String buildRuleName) {
    return failedBuildResultWithException(buildRuleName, new Exception(buildRuleName));
  }

  private static BuildResult failedBuildResultWithException(
      String buildRuleName, Throwable failure) {
    return BuildResult.builder()
        .setStatus(BuildRuleStatus.FAIL)
        .setFailureOptional(failure)
        .setRule(buildRule(buildRuleName))
        .setCacheResult(CacheResult.miss())
        .build();
  }

  private static BuildResult successBuildResult(String buildRuleName) {
    return BuildResult.builder()
        .setStatus(BuildRuleStatus.SUCCESS)
        .setRule(buildRule(buildRuleName))
        .setSuccessOptional(BuildRuleSuccessType.BUILT_LOCALLY)
        .setCacheResult(CacheResult.miss())
        .build();
  }

  private static BuildResult cancelledBuildResult(String buildRuleName, Throwable cause) {
    return BuildResult.builder()
        .setStatus(BuildRuleStatus.CANCELED)
        .setRule(buildRule(buildRuleName))
        .setFailureOptional(cause)
        .setCacheResult(CacheResult.miss())
        .build();
  }
}
