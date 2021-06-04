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

package com.facebook.buck.workertool.impl.request;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.AbstractMessage;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Holds execution request details. This represents a currently executing external worker tool
 * request of potentially many actions
 */
public abstract class ExecutionRequest<T extends AbstractMessage> {

  public abstract ImmutableList<ExecutingAction> getExecutionActions();

  abstract Consumer<Iterable<ActionId>> getOnCloseAction();

  /** Processes result event associated with request. */
  public void processResultEvent(ResultEvent event) {
    verifyExecuting();

    ActionId actionId = ActionId.of(event.getActionId());
    ExecutingAction executingAction = getFirstNotCompletedAction();
    ActionId actionActionId = executingAction.getActionId();
    Preconditions.checkState(
        actionId.equals(actionActionId),
        "Received action id %s is not equals to expected one %s. Currently executing actions: %s",
        actionId,
        actionActionId,
        getHumanReadableId());

    SettableFuture<ResultEvent> resultEventFuture = executingAction.getResultEventFuture();
    resultEventFuture.set(event);
  }

  /**
   * Marks request as finished by processing finished event. Also verifies that all actions are
   * completed and runs on close actions.
   */
  public void setFinished(T event) {
    processFinishedEvent(event);
    verifyAllActionsAreDone();
    runOnCloseActions();
  }

  @SuppressWarnings("unused") // mute incorrect ant linter error
  void processFinishedEvent(T event) {}

  private void runOnCloseActions() {
    Iterator<ActionId> actionIdIterator =
        getExecutionActions().stream().map(ExecutingAction::getActionId).iterator();
    getOnCloseAction().accept(() -> actionIdIterator);
  }

  private ExecutingAction getFirstNotCompletedAction() {
    for (ExecutingAction action : getExecutionActions()) {
      if (!action.getResultEventFuture().isDone()) {
        return action;
      }
    }
    throw new IllegalStateException(
        String.format("Can't find not completed action. Actions: %s.", getHumanReadableId()));
  }

  public abstract void verifyExecuting();

  /** Verifies that request contains an action id. */
  public void verifyContainsAction(ActionId actionId) {
    Preconditions.checkState(
        getExecutionActions().stream().map(ExecutingAction::getActionId).anyMatch(actionId::equals),
        "Action id: %s is not found among currently execution actions: %s",
        actionId,
        getHumanReadableId());
  }

  void verifyAllActionsAreDone() {
    for (ExecutingAction executingAction : getExecutionActions()) {
      SettableFuture<ResultEvent> future = executingAction.getResultEventFuture();
      if (!future.isDone()) {
        throw new IllegalStateException(
            executingAction.getActionId() + " associated future is not yet done");
      }
    }
  }

  /**
   * For each future associated with the request check if it is done, and if not then terminate it
   * with an exception.
   */
  public void terminateWithExceptionIfNotDone(Supplier<Exception> exceptionSupplier) {
    for (ExecutingAction executingAction : getExecutionActions()) {
      SettableFuture<ResultEvent> resultEventFuture = executingAction.getResultEventFuture();
      if (!resultEventFuture.isDone()) {
        resultEventFuture.setException(exceptionSupplier.get());
      }
    }
  }

  protected String getHumanReadableId() {
    return getExecutionActions().stream()
        .map(ExecutingAction::getActionId)
        .map(ActionId::getValue)
        .collect(Collectors.joining(", "));
  }

  /** Holds execution request details for a single command */
  @BuckStyleValue
  public abstract static class SingleExecutionRequest extends ExecutionRequest<ResultEvent> {

    @Override
    @Value.Derived
    public ImmutableList<ExecutingAction> getExecutionActions() {
      return ImmutableList.of(getExecutionAction());
    }

    @Override
    @Value.Derived
    Consumer<Iterable<ActionId>> getOnCloseAction() {
      return actionIds -> getSingleOnCloseAction().accept(Iterables.getOnlyElement(actionIds));
    }

    public abstract ExecutingAction getExecutionAction();

    abstract Consumer<ActionId> getSingleOnCloseAction();

    @Override
    public void verifyExecuting() {
      ExecutingAction executionAction = getExecutionAction();
      Preconditions.checkState(
          !executionAction.getResultEventFuture().isDone(),
          String.format("Execution is finished : %s", getHumanReadableId()));
    }

    @Override
    public void processResultEvent(ResultEvent event) {
      super.processResultEvent(event);
      setFinished(event);
    }
  }

  /** Holds execution request details for a pipelining command */
  @BuckStyleValue
  public abstract static class PipeliningExecutionRequest
      extends ExecutionRequest<PipelineFinishedEvent> {

    abstract SettableFuture<PipelineFinishedEvent> getPipelineFinishedFuture();

    @Override
    public abstract ImmutableList<ExecutingAction> getExecutionActions();

    @Override
    abstract Consumer<Iterable<ActionId>> getOnCloseAction();

    @Override
    public void verifyExecuting() {
      Preconditions.checkState(
          !getPipelineFinishedFuture().isDone(),
          String.format("Pipeline is finished : %s", getHumanReadableId()));
    }

    @Override
    void processFinishedEvent(PipelineFinishedEvent event) {
      getPipelineFinishedFuture().set(event);
    }

    @Override
    void verifyAllActionsAreDone() {
      super.verifyAllActionsAreDone();
      if (!getPipelineFinishedFuture().isDone()) {
        throw new IllegalStateException("Pipeline finished event future is not yet done");
      }
    }

    @Override
    public void terminateWithExceptionIfNotDone(Supplier<Exception> exceptionSupplier) {
      super.terminateWithExceptionIfNotDone(exceptionSupplier);
      getPipelineFinishedFuture().setException(exceptionSupplier.get());
    }
  }

  /**
   * Creates and registers in the passed {@code map} an instance of {@link SingleExecutionRequest}
   */
  public static SingleExecutionRequest singleExecution(
      ActionId actionId, Map<ActionId, ExecutionRequest<?>> map) {
    Consumer<ActionId> onCloseAction = id -> removeFromRequestMap(map, id);
    // create request with on close action
    SingleExecutionRequest request =
        ImmutableSingleExecutionRequest.ofImpl(ExecutingAction.of(actionId), onCloseAction);
    // run on create action
    registerNewRequest(map, actionId, request);
    // return request
    return request;
  }

  /**
   * Creates and registers in the passed {@code map} an instance of {@link
   * PipeliningExecutionRequest}
   */
  public static PipeliningExecutionRequest pipeliningExecution(
      ImmutableList<ExecutingAction> executingActions,
      SettableFuture<PipelineFinishedEvent> future,
      Map<ActionId, ExecutionRequest<?>> map) {
    Consumer<Iterable<ActionId>> onCloseAction = actionIds -> removeFromRequestMap(map, actionIds);
    // create request with on close action
    ImmutablePipeliningExecutionRequest request =
        ImmutablePipeliningExecutionRequest.ofImpl(future, executingActions, onCloseAction);
    // run on create action
    for (ExecutingAction executingAction : executingActions) {
      registerNewRequest(map, executingAction.getActionId(), request);
    }
    // return request
    return request;
  }

  private static void registerNewRequest(
      Map<ActionId, ExecutionRequest<?>> map,
      ActionId actionId,
      ExecutionRequest<?> executionRequest) {
    ExecutionRequest<?> previousValue = map.put(actionId, executionRequest);
    Preconditions.checkState(previousValue == null);
  }

  private static void removeFromRequestMap(
      Map<ActionId, ExecutionRequest<?>> map, Iterable<ActionId> actionIds) {
    for (ActionId actionId : actionIds) {
      removeFromRequestMap(map, actionId);
    }
  }

  private static void removeFromRequestMap(
      Map<ActionId, ExecutionRequest<?>> map, ActionId actionId) {
    ExecutionRequest<?> removedRequest = map.remove(actionId);
    Preconditions.checkState(removedRequest != null);
  }
}
