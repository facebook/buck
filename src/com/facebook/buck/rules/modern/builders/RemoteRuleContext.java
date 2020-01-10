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

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** This holds information about rule states. */
public class RemoteRuleContext {
  // guard should only be set once. The left value indicates that it has been cancelled and holds
  // the reason, a right value indicates that it has passed the point of no return and can no
  // longer be cancelled.
  AtomicReference<Either<Throwable, Object>> guard = new AtomicReference<>();
  ConcurrentLinkedQueue<Consumer<Throwable>> callbackQueue = new ConcurrentLinkedQueue<>();
  RemoteExecutionActionEvent.State actionState;
  RemoteExecutionActionEvent.State prevState;
  RemoteExecutionActionEvent.State lastNonTerminalState;
  final BuildRule buildRule;
  final BuckEventBus eventBus;
  Map<RemoteExecutionActionEvent.State, Long> timeMsInState;
  Map<RemoteExecutionActionEvent.State, Long> timeMsAfterState;
  long prevStateTime;
  private static final Logger LOG = Logger.get(RemoteExecutionStrategy.class);

  public RemoteRuleContext(BuckEventBus eventBus, BuildRule rule) {
    this.buildRule = rule;
    this.eventBus = eventBus;
    this.actionState = RemoteExecutionActionEvent.State.WAITING;
    this.prevState = RemoteExecutionActionEvent.State.WAITING;
    this.timeMsInState = new ConcurrentHashMap<>();
    this.timeMsAfterState = new ConcurrentHashMap<>();
    this.prevStateTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    this.lastNonTerminalState = RemoteExecutionActionEvent.State.WAITING;
  }

  /** Whether action has been cancelled. */
  public boolean isCancelled() {
    return guard.get() != null && guard.get().isLeft();
  }

  /** Get Throwable which caused action to cancel. */
  public Throwable getCancelReason() {
    Verify.verify(isCancelled());
    assert guard.get() != null;
    return guard.get().getLeft();
  }

  /** Call when action has been cancelled. */
  public void cancel(Throwable reason) {
    guard.compareAndSet(null, Either.ofLeft(reason));
    if (isCancelled()) {
      processCallbackQueue();
    }
  }

  /** Call Try Start to set the guard. */
  public boolean tryStart() {
    return guard.compareAndSet(null, Either.ofRight(new Object()));
  }

  /** Action has been cancelled. */
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

  /** Called when Action state is changed. */
  public Scope enterState(
      RemoteExecutionActionEvent.State state, Optional<Protocol.Digest> actionDigest) {
    Preconditions.checkState(
        state.ordinal() > prevState.ordinal(),
        "Cannot Enter State: " + state + " from: " + actionState);
    timeMsAfterState.put(
        prevState, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - prevStateTime);
    actionState = state;
    if (!RemoteExecutionActionEvent.isTerminalState(state)) {
      lastNonTerminalState = state;
    }

    long startMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    Scope inner = RemoteExecutionActionEvent.sendEvent(eventBus, state, buildRule, actionDigest);

    return () -> {
      if (actionState != RemoteExecutionActionEvent.State.WAITING) {
        timeMsInState.put(actionState, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - startMs);
        prevState = actionState;
        prevStateTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        actionState = RemoteExecutionActionEvent.State.WAITING;
      }
      inner.close();
    };
  }
}
