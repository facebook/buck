/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.event;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Objects;

/**
 * Events for timing the starting of android events.
 */
public abstract class StartActivityEvent extends AbstractBuckEvent implements LeafEvent {
  private final BuildTarget buildTarget;
  private final String activityName;

  protected StartActivityEvent(EventKey eventKey, BuildTarget buildTarget, String activityName) {
    super(eventKey);
    this.buildTarget = buildTarget;
    this.activityName = activityName;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public String getActivityName() {
    return activityName;
  }

  @Override
  public String getCategory() {
    return "start_activity";
  }

  @Override
  protected String getValueString() {
    return String.format("%s %s", getBuildTarget().getFullyQualifiedName(), getActivityName());
  }

  public static Started started(BuildTarget buildTarget, String activityName) {
    return new Started(buildTarget, activityName);
  }

  public static Finished finished(Started started, boolean success) {
    return new Finished(started, success);
  }

  public static class Started extends StartActivityEvent {
    protected Started(BuildTarget buildTarget, String activityName) {
      super(EventKey.unique(), buildTarget, activityName);
    }

    @Override
    public String getEventName() {
      return "StartActivityStarted";
    }
  }

  public static class Finished extends StartActivityEvent {
    private final boolean success;

    protected Finished(Started started, boolean success) {
      super(started.getEventKey(), started.getBuildTarget(), started.getActivityName());
      this.success = success;
    }

    public boolean isSuccess() {
      return success;
    }

    @Override
    public String getEventName() {
      return "StartActivityFinished";
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }
      // Because super.equals compares the EventKey, getting here means that we've somehow managed
      // to create 2 Finished events for the same Started event.
      throw new UnsupportedOperationException("Multiple conflicting Finished events detected.");
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), isSuccess());
    }
  }
}
