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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import io.grpc.Status;
import java.util.Optional;
import java.util.OptionalInt;

/** Event containing info about single BuildRule executions inside LocalFallbackStrategy. */
public abstract class LocalFallbackEvent extends AbstractBuckEvent {

  protected LocalFallbackEvent() {
    super(EventKey.unique());
  }

  /** Summary result of an execution. */
  public enum Result {
    SUCCESS,
    FAIL,
    EXCEPTION,
    NOT_RUN,
    INTERRUPTED,
    CANCELLED
  }

  public static Started createStarted(String buildTarget) {
    return new Started(buildTarget);
  }

  /** When the LocalFallbackStrategy starts processing a single BuildRule. */
  public static class Started extends LocalFallbackEvent {
    private final String buildTarget;

    private Started(String buildTarget) {
      this.buildTarget = buildTarget;
    }

    public Finished createFinished(
        Result remoteResult,
        Result localResult,
        long remoteDurationMillis,
        Optional<String> remoteErrorMessage,
        Status remoteGrpcStatus,
        State lastNonTerminalState,
        OptionalInt exitCode,
        Optional<RemoteExecutionMetadata> remoteExecutionMetadata) {
      return new Finished(
          this,
          remoteResult,
          localResult,
          remoteDurationMillis,
          remoteErrorMessage,
          remoteGrpcStatus,
          lastNonTerminalState,
          exitCode,
          remoteExecutionMetadata);
    }

    public String getBuildTarget() {
      return buildTarget;
    }

    @Override
    protected String getValueString() {
      return String.format("BuildTarget=[%s]", buildTarget);
    }
  }

  /** When the LocalFallbackStrategy finished processing a single BuildRule. */
  public static class Finished extends LocalFallbackEvent {
    private final Started startedEvent;
    private final Result localResult;
    private final long remoteDurationMillis;
    private final Result remoteResult;
    private final Optional<String> remoteErrorMessage;
    private final Status remoteGrpcStatus;
    private final State lastNonTerminalState;
    private final OptionalInt exitCode;
    private final Optional<RemoteExecutionMetadata> remoteExecutionMetadata;

    private Finished(
        Started startedEvent,
        Result remoteResult,
        Result localResult,
        long remoteDurationMillis,
        Optional<String> remoteErrorMessage,
        Status remoteGrpcStatus,
        State lastNonTerminalState,
        OptionalInt exitCode,
        Optional<RemoteExecutionMetadata> remoteExecutionMetadata) {
      this.startedEvent = startedEvent;
      this.remoteResult = remoteResult;
      this.localResult = localResult;
      this.remoteDurationMillis = remoteDurationMillis;
      this.remoteErrorMessage = remoteErrorMessage;
      this.remoteGrpcStatus = remoteGrpcStatus;
      this.lastNonTerminalState = lastNonTerminalState;
      this.exitCode = exitCode;
      this.remoteExecutionMetadata = remoteExecutionMetadata;
    }

    public Started getStartedEvent() {
      return startedEvent;
    }

    public Result getRemoteResult() {
      return remoteResult;
    }

    public Optional<String> getRemoteErrorMessage() {
      return remoteErrorMessage;
    }

    public Result getLocalResult() {
      return localResult;
    }

    public long getRemoteDurationMillis() {
      return remoteDurationMillis;
    }

    public Status getRemoteGrpcStatus() {
      return remoteGrpcStatus;
    }

    public State getLastNonTerminalState() {
      return lastNonTerminalState;
    }

    public OptionalInt getExitCode() {
      return exitCode;
    }

    public Optional<RemoteExecutionMetadata> getRemoteExecutionMetadata() {
      return remoteExecutionMetadata;
    }

    @Override
    protected String getValueString() {
      return String.format(
          "StartedEvent=[%s] RemoteResult=[%s] LocalResult=[%s]",
          startedEvent.getValueString(), remoteResult, localResult);
    }

    public long getFullDurationMillis() {
      return this.getTimestampMillis() - startedEvent.getTimestampMillis();
    }

    public boolean wasExecutionSuccessful() {
      return remoteResult == Result.SUCCESS || localResult == Result.SUCCESS;
    }
  }

  @Override
  public String getEventName() {
    return getClass().getSimpleName();
  }
}
