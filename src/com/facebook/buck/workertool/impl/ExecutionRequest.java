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

package com.facebook.buck.workertool.impl;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.downward.model.PipelineFinishedEvent;
import com.facebook.buck.downward.model.ResultEvent;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * Holds execution request details. This represents a currently executing external worker tool
 * request of potentially many actions
 */
abstract class ExecutionRequest {

  abstract ImmutableList<ExecutingAction> getExecutionActions();

  void verifyAllActionsAreDone() {
    for (ExecutingAction executingAction : getExecutionActions()) {
      SettableFuture<ResultEvent> future = executingAction.getResultEventFuture();
      if (!future.isDone()) {
        throw new IllegalStateException(
            executingAction.getActionId() + " associated future is not yet done");
      }
    }
  }

  void terminateWithExceptionIfNotDone(Supplier<Exception> exceptionSupplier) {
    for (ExecutingAction executingAction : getExecutionActions()) {
      SettableFuture<ResultEvent> resultEventFuture = executingAction.getResultEventFuture();
      if (!resultEventFuture.isDone()) {
        resultEventFuture.setException(exceptionSupplier.get());
      }
    }
  }

  final String getHumanReadableId() {
    return getExecutionActions().stream()
        .map(ExecutingAction::getActionId)
        .map(ActionId::getValue)
        .collect(Collectors.joining(", "));
  }

  static SingleExecutionRequest singleExecution(ActionId actionId) {
    return SingleExecutionRequest.of(actionId);
  }

  static PipeliningExecutionRequest pipeliningExecution(
      ImmutableList<ExecutingAction> executingActions,
      SettableFuture<PipelineFinishedEvent> future) {
    return ImmutablePipeliningExecutionRequest.ofImpl(future, executingActions);
  }

  /** Holds execution request details for a single command */
  @BuckStyleValue
  abstract static class SingleExecutionRequest extends ExecutionRequest {

    @Override
    @Value.Derived
    ImmutableList<ExecutingAction> getExecutionActions() {
      return ImmutableList.of(getExecutionAction());
    }

    abstract ExecutingAction getExecutionAction();

    public static SingleExecutionRequest of(ActionId actionId) {
      return ImmutableSingleExecutionRequest.ofImpl(ExecutingAction.of(actionId));
    }
  }

  /** Holds execution request details for a pipelining command */
  @BuckStyleValue
  abstract static class PipeliningExecutionRequest extends ExecutionRequest {

    abstract SettableFuture<PipelineFinishedEvent> getPipelineFinishedFuture();

    @Override
    abstract ImmutableList<ExecutingAction> getExecutionActions();

    @Override
    void verifyAllActionsAreDone() {
      super.verifyAllActionsAreDone();
      if (!getPipelineFinishedFuture().isDone()) {
        throw new IllegalStateException("Pipeline finished event future is not yet done");
      }
    }

    @Override
    void terminateWithExceptionIfNotDone(Supplier<Exception> exceptionSupplier) {
      super.terminateWithExceptionIfNotDone(exceptionSupplier);
      getPipelineFinishedFuture().setException(exceptionSupplier.get());
    }
  }
}
