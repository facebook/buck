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

package com.facebook.buck.remoteexecution.event;

import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.util.Scope;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** Tracks events related to Remote Execution Actions. */
public abstract class RemoteExecutionActionEvent extends AbstractBuckEvent
    implements WorkAdvanceEvent {

  protected RemoteExecutionActionEvent(EventKey eventKey) {
    super(eventKey);
  }

  /** The current state of a Remote Execution Actions. */
  public enum State {
    WAITING("wait"),
    COMPUTING_ACTION("comp"),
    UPLOADING_INPUTS("upl_in"),
    UPLOADING_ACTION("upl_act"),
    EXECUTING("exec"),
    DELETING_STALE_OUTPUTS("del"),
    MATERIALIZING_OUTPUTS("dwl"),
    ACTION_SUCCEEDED("suc"),
    ACTION_FAILED("fail"),
    ACTION_CANCELLED("cncl");

    private final String abbreviateName;

    State(String abbreviateName) {
      this.abbreviateName = abbreviateName;
    }

    /** Abbreviated name for the current state. */
    @JsonView(JsonViews.MachineReadableLog.class)
    public String getAbbreviateName() {
      return abbreviateName;
    }
  }

  /** Takes care of sending both Started and Finished events within a Scope. */
  public static Scope sendEvent(
      BuckEventBus eventBus, State state, BuildRule buildRule, Optional<Digest> actionDigest) {
    final Started startedEvent = new Started(state, buildRule, actionDigest);
    eventBus.post(startedEvent);
    final Scope leftEventScope = LeafEvents.scope(eventBus, state.toString().toLowerCase(), false);
    return () -> {
      leftEventScope.close();
      eventBus.post(new Finished(startedEvent));
    };
  }

  /** Sends the terminal event of an action [FAIL|SUCCESS]. */
  public static void sendTerminalEvent(
      BuckEventBus eventBus,
      State state,
      BuildRule buildRule,
      Optional<Digest> actionDigest,
      Optional<ExecutedActionMetadata> executedActionMetadata,
      Optional<RemoteExecutionMetadata> remoteExecutionMetadata,
      Optional<Map<State, Long>> stateMetadata,
      Optional<Map<State, Long>> stateWaitingMetadata,
      Status grpcStatus,
      State lastNonTerminalState,
      OptionalInt exitCode) {
    final Terminal event =
        new Terminal(
            state,
            buildRule,
            actionDigest,
            executedActionMetadata,
            remoteExecutionMetadata,
            stateMetadata,
            stateWaitingMetadata,
            grpcStatus,
            lastNonTerminalState,
            exitCode);
    eventBus.post(event);
  }

  public static void sendScheduledEvent(BuckEventBus eventBus, BuildRule buildRule) {
    eventBus.post(new Scheduled(buildRule));
  }

  /** Sending the InputsUploaded event */
  public static void sendInputsUploadedEventIfNeed(
      BuckEventBus eventBus, BuildRule buildRule, List<InputsUploaded.LargeBlob> largeBlobs) {
    if (largeBlobs.isEmpty()) {
      return;
    }

    eventBus.post(new InputsUploaded(buildRule, largeBlobs));
  }

  public static boolean isTerminalState(State state) {
    return state == State.ACTION_FAILED
        || state == State.ACTION_SUCCEEDED
        || state == State.ACTION_CANCELLED;
  }

  /** Describes the event which occurs right after inputs were uploaded to the CAS. */
  public static class InputsUploaded extends RemoteExecutionActionEvent {
    private final BuildRule rule;
    private final List<LargeBlob> largeBlobs;

    protected InputsUploaded(BuildRule rule, List<LargeBlob> largeBlobs) {
      super(EventKey.unique());

      this.rule = rule;
      this.largeBlobs = largeBlobs;
    }

    @Override
    protected String getValueString() {
      return "INPUTS_UPLOADED";
    }

    public List<LargeBlob> getLargeBlobs() {
      return largeBlobs;
    }

    public BuildRule getBuildRule() {
      return rule;
    }

    /** Large blob definition */
    public static class LargeBlob {
      public final String fileName;
      public final Digest digest;

      public LargeBlob(String fileName, Digest digest) {
        this.fileName = fileName;
        this.digest = digest;
      }
    }
  }

  /** Sends a one off terminal event for a Remote Execution Action. */
  public static class Terminal extends RemoteExecutionActionEvent {
    private final State state;
    private final BuildRule buildRule;
    private final Optional<Digest> actionDigest;
    private final Optional<ExecutedActionMetadata> executedActionMetadata;
    private final Optional<RemoteExecutionMetadata> remoteExecutionMetadata;
    private final Optional<Map<State, Long>> stateMetadata;
    private final Optional<Map<State, Long>> stateWaitingMetadata;
    private final Status grpcStatus;
    private final State lastNonTerminalState;
    private final OptionalInt exitCode;

    @VisibleForTesting
    Terminal(
        State state,
        BuildRule buildRule,
        Optional<Digest> actionDigest,
        Optional<ExecutedActionMetadata> executedActionMetadata,
        Optional<RemoteExecutionMetadata> remoteExecutionMetadata,
        Optional<Map<State, Long>> stateMetadata,
        Optional<Map<State, Long>> stateWaitingMetadata,
        Status grpcStatus,
        State lastNonTerminalState,
        OptionalInt exitCode) {
      super(EventKey.unique());
      Preconditions.checkArgument(
          RemoteExecutionActionEvent.isTerminalState(state),
          "State [%s] is not a terminal state.",
          state);
      this.state = state;
      this.buildRule = buildRule;
      this.actionDigest = actionDigest;
      this.executedActionMetadata = executedActionMetadata;
      this.remoteExecutionMetadata = remoteExecutionMetadata;
      this.stateMetadata = stateMetadata;
      this.stateWaitingMetadata = stateWaitingMetadata;
      this.grpcStatus = grpcStatus;
      this.lastNonTerminalState = lastNonTerminalState;
      this.exitCode = exitCode;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public State getState() {
      return state;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRule getBuildRule() {
      return buildRule;
    }

    @JsonIgnore
    public BuildTarget getBuildTarget() {
      return buildRule.getBuildTarget();
    }

    @JsonIgnore
    public Optional<Digest> getActionDigest() {
      return actionDigest;
    }

    @JsonIgnore
    public Optional<ExecutedActionMetadata> getExecutedActionMetadata() {
      return executedActionMetadata;
    }

    @JsonIgnore
    public Optional<RemoteExecutionMetadata> getRemoteExecutionMetadata() {
      return remoteExecutionMetadata;
    }

    @JsonIgnore
    public Optional<Map<State, Long>> getStateMetadata() {
      return stateMetadata;
    }

    @JsonIgnore
    public Optional<Map<State, Long>> getStateWaitingMetadata() {
      return stateWaitingMetadata;
    }

    public Status getGrpcStatus() {
      return grpcStatus;
    }

    public State getLastNonTerminalState() {
      return lastNonTerminalState;
    }

    public OptionalInt getExitCode() {
      return exitCode;
    }

    @JsonIgnore
    @Override
    protected String getValueString() {
      return state.toString();
    }
  }

  /** An action just moved into this state. */
  public static class Started extends RemoteExecutionActionEvent {
    private final State state;
    private final BuildRule buildRule;
    private final Optional<Digest> actionDigest;

    @VisibleForTesting
    Started(State state, BuildRule buildRule, Optional<Digest> actionDigest) {
      super(EventKey.unique());
      Preconditions.checkArgument(
          !RemoteExecutionActionEvent.isTerminalState(state),
          "Argument state [%s] cannot be a terminal state.",
          state);
      this.buildRule = buildRule;
      this.state = state;
      this.actionDigest = actionDigest;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public State getState() {
      return state;
    }

    @JsonIgnore
    public BuildTarget getBuildTarget() {
      return buildRule.getBuildTarget();
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRule getBuildRule() {
      return buildRule;
    }

    @JsonIgnore
    public Optional<Digest> getActionDigest() {
      return actionDigest;
    }

    @Override
    protected String getValueString() {
      return state.toString();
    }
  }

  /**
   * Indicates that a remote execution event has been scheduled. This should be sent once for every
   * build target that will have any other remote execution action events (and should be sent before
   * any others).
   */
  public static class Scheduled extends RemoteExecutionActionEvent {
    private final BuildRule buildRule;

    protected Scheduled(BuildRule buildRule) {
      super(EventKey.unique());
      this.buildRule = buildRule;
    }

    @JsonIgnore
    @Override
    protected String getValueString() {
      return "scheduled";
    }

    @JsonIgnore
    public BuildTarget getBuildTarget() {
      return buildRule.getBuildTarget();
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRule getBuildRule() {
      return buildRule;
    }
  }

  /** An action just exited from this state. */
  public static class Finished extends RemoteExecutionActionEvent {

    private final Started startedEvent;

    @VisibleForTesting
    Finished(Started startedEvent) {
      super(startedEvent.getEventKey());
      this.startedEvent = startedEvent;
    }

    public Started getStartedEvent() {
      return startedEvent;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRule getBuildRule() {
      return startedEvent.getBuildRule();
    }

    @Override
    protected String getValueString() {
      return startedEvent.getValueString();
    }
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }

  public static String actionDigestToString(Digest actionDigest) {
    return String.format("%s:%d", actionDigest.getHash(), actionDigest.getSize());
  }
}
