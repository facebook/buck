/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

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
    NOT_RUN
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

    public Finished createFinished(Result remoteResult, Result localResult) {
      return new Finished(this, remoteResult, localResult);
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
    private final Result remoteResult;
    private final Result localResult;

    private Finished(Started startedEvent, Result remoteResult, Result localResult) {
      this.startedEvent = startedEvent;
      this.remoteResult = remoteResult;
      this.localResult = localResult;
    }

    public Started getStartedEvent() {
      return startedEvent;
    }

    public Result getRemoteResult() {
      return remoteResult;
    }

    public Result getLocalResult() {
      return localResult;
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
