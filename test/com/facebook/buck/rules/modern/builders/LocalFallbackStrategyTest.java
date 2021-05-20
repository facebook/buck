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

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ForwardingIsolatedEventBus;
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
import org.junit.Before;
import org.junit.Test;

public class LocalFallbackStrategyTest {
  private static final String RULE_NAME = "//topspin:rule";

  private RemoteExecutionStrategy.RemoteExecutionStrategyBuildResult strategyBuildResult;
  private BuildStrategyContext buildStrategyContext;
  private ListeningExecutorService directExecutor;
  private BuckEventBus eventBus;
  private RemoteRuleContext ruleContext;
  private StepExecutionContext executionContext;

  @Before
  public void setUp() {
    strategyBuildResult =
        createMock(RemoteExecutionStrategy.RemoteExecutionStrategyBuildResult.class);
    buildStrategyContext = createMock(BuildStrategyContext.class);
    directExecutor = MoreExecutors.newDirectExecutorService();
    eventBus = createNiceMock(BuckEventBus.class);
    ruleContext = new RemoteRuleContext(eventBus, buildRule(RULE_NAME));
    executionContext = TestExecutionContext.newInstance();
  }

  @Test
  public void testRemoteSuccess() throws ExecutionException, InterruptedException {
    BuildResult buildResult = successBuildResult("//finished:successfully");
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(buildResult)))
        .times(2);
    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertEquals(buildResult, fallbackStrategyBuildResult.getBuildResult().get().get());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteInterruptedException() throws ExecutionException, InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(
            Futures.immediateFailedFuture(new InterruptedException("This did not go well...")))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteGrpcException() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(capture(eventCapture));
    expectLastCall().times(6);
    expect(eventBus.isolated()).andStubReturn(new ForwardingIsolatedEventBus(eventBus));
    replay(eventBus);

    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(Status.DEADLINE_EXCEEDED.asRuntimeException()))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    replay(strategyBuildResult, buildStrategyContext);

    ruleContext.enterState(State.UPLOADING_ACTION, Optional.empty());
    ruleContext.enterState(State.EXECUTING, Optional.empty());
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());
    verify(strategyBuildResult, buildStrategyContext);

    List<LocalFallbackEvent> events = eventCapture.getValues();
    assertEquals(6, events.size());
    assertTrue(events.get(4) instanceof LocalFallbackEvent.Started);
    assertTrue(events.get(5) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(5);
    assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.DEADLINE_EXCEEDED);
    assertEquals(finishedEvent.getLastNonTerminalState(), State.EXECUTING);
    assertEquals(finishedEvent.getExitCode(), OptionalInt.empty());
  }

  @Test
  public void testExitCodeAndFallback()
      throws ExecutionException, InterruptedException, IOException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(capture(eventCapture));
    expectLastCall().times(2);
    replay(eventBus);

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
              public StepExecutionResult execute(StepExecutionContext context) {
                throw new RuntimeException();
              }
            },
            executionContext,
            StepExecutionResult.builder().setExitCode(1).setStderr("").build(),
            remoteExecutionMetadata);

    // Just here to test if this is serializable by jackson, as we do Log.warn this.
    new ObjectMapper().writeValueAsString(exc);

    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exc))
        .times(2);
    BuildResult localResult = successBuildResult("//local/did:though");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();
    expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, false);
    assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());
    verify(strategyBuildResult, buildStrategyContext);

    List<LocalFallbackEvent> events = eventCapture.getValues();
    assertTrue(events.get(0) instanceof LocalFallbackEvent.Started);
    assertTrue(events.get(1) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(1);
    assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.OK);
    assertEquals(finishedEvent.getExitCode(), OptionalInt.of(1));
    assertEquals(
        finishedEvent.getRemoteExecutionMetadata().get().getWorkerInfo().getHostname(), mockWorker);
  }

  @Test
  public void testCancellation() throws ExecutionException, InterruptedException {
    Exception cause = new RuntimeException();
    BuildResult cancelledResult = cancelledBuildResult(RULE_NAME, cause);
    SettableFuture<Optional<BuildResult>> strategyResult = SettableFuture.create();
    expect(strategyBuildResult.getBuildResult()).andReturn(strategyResult).anyTimes();
    strategyBuildResult.cancel(cause);
    expect(buildStrategyContext.createCancelledResult(cause)).andReturn(cancelledResult);
    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    fallbackStrategyBuildResult.cancel(cause);
    assertEquals(cancelledResult, fallbackStrategyBuildResult.getBuildResult().get().get());
    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionError() throws ExecutionException, InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//super:cool"))))
        .times(2);
    BuildResult localResult = successBuildResult("//hurrah:weeeee");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionErrorFallbackDisabled()
      throws ExecutionException, InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//super:cool"))))
        .times(2);

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, false, false, true);
    assertEquals(
        BuildRuleStatus.FAIL, fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionCorruptArtifactError()
      throws ExecutionException, InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(
            Futures.immediateFuture(
                Optional.of(
                    failedBuildResultWithException(
                        "//super:cool",
                        new MultiThreadedBlobUploader.CorruptArtifactException(
                            "uhoh", "description")))))
        .times(2);

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, true, true);
    assertEquals(
        BuildRuleStatus.FAIL, fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionExceptionFallbackDisabled() throws InterruptedException {
    Exception exception = new Exception("local failed miserably.");
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exception))
        .times(2);
    expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);
    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, false, false, true);
    assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      fail("Should've thrown...");
    } catch (ExecutionException e) {
      assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testRemoteActionExceptionFallbackDisabledForBuildError() throws InterruptedException {
    Exception exception =
        StepFailedException.createForFailingStepWithExitCode(
            new AbstractExecutionStep("remote_execution") {
              @Override
              public StepExecutionResult execute(StepExecutionContext context) {
                throw new RuntimeException();
              }
            },
            executionContext,
            StepExecutionResult.builder().setExitCode(1).setStderr("").build());
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFailedFuture(exception))
        .times(2);
    expect(strategyBuildResult.getRuleContext()).andReturn(ruleContext);
    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, false);
    assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      fail("Should've thrown...");
    } catch (ExecutionException e) {
      assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testLocalError() throws ExecutionException, InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//will/fail:remotely"))))
        .times(2);
    BuildResult localResult = failedBuildResult("//will/fail:locally");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFuture(Optional.of(localResult)))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertEquals(
        localResult.getStatus(),
        fallbackStrategyBuildResult.getBuildResult().get().get().getStatus());

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testLocalException() throws InterruptedException {
    expect(strategyBuildResult.getBuildResult())
        .andReturn(Futures.immediateFuture(Optional.of(failedBuildResult("//will/fail:remotely"))))
        .times(2);
    Exception exception = new Exception("local failed miserably.");
    expect(buildStrategyContext.runWithDefaultBehavior())
        .andReturn(Futures.immediateFailedFuture(exception))
        .once();
    expect(buildStrategyContext.getExecutorService()).andReturn(directExecutor).once();

    replay(strategyBuildResult, buildStrategyContext);
    FallbackStrategyBuildResult fallbackStrategyBuildResult =
        new FallbackStrategyBuildResult(
            RULE_NAME, strategyBuildResult, buildStrategyContext, eventBus, true, false, true);
    assertTrue(fallbackStrategyBuildResult.getBuildResult().isDone());
    try {
      fallbackStrategyBuildResult.getBuildResult().get();
      fail("Should've thrown...");
    } catch (ExecutionException e) {
      assertEquals(exception.getMessage(), e.getCause().getMessage());
    }

    verify(strategyBuildResult, buildStrategyContext);
  }

  @Test
  public void testEventsAreSent() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(capture(eventCapture));
    expectLastCall().times(2);
    replay(eventBus);

    testRemoteActionError();

    List<LocalFallbackEvent> events = eventCapture.getValues();
    assertTrue(events.get(0) instanceof LocalFallbackEvent.Started);
    assertTrue(events.get(1) instanceof LocalFallbackEvent.Finished);
    LocalFallbackEvent.Finished finishedEvent = (LocalFallbackEvent.Finished) events.get(1);
    assertEquals(finishedEvent.getRemoteGrpcStatus(), Status.OK);
  }

  @Test
  public void testInterruptedState() throws ExecutionException, InterruptedException {
    Capture<LocalFallbackEvent> eventCapture = Capture.newInstance(CaptureType.ALL);
    eventBus.post(capture(eventCapture));
    expectLastCall().times(2);
    replay(eventBus);

    testRemoteInterruptedException();

    List<LocalFallbackEvent> events = eventCapture.getValues();
    LocalFallbackEvent.Finished event = (LocalFallbackEvent.Finished) events.get(1);
    assertEquals(LocalFallbackEvent.Result.INTERRUPTED, event.getRemoteResult());
    assertEquals(LocalFallbackEvent.Result.SUCCESS, event.getLocalResult());
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
