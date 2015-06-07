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
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class StartActivityEvent extends AbstractBuckEvent implements LeafEvent {
  private final BuildTarget buildTarget;
  private final String activityName;

  protected StartActivityEvent(BuildTarget buildTarget, String activityName) {
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

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof StartActivityEvent)) {
      return false;
    }

    StartActivityEvent that = (StartActivityEvent) event;

    return Objects.equal(getBuildTarget(), that.getBuildTarget()) &&
        Objects.equal(getActivityName(), that.getActivityName());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getActivityName(), getBuildTarget());
  }

  public static Started started(BuildTarget buildTarget, String activityName) {
    return new Started(buildTarget, activityName);
  }

  public static Finished finished(BuildTarget buildTarget, String activityName, boolean success) {
    return new Finished(buildTarget, activityName, success);
  }

  public static class Started extends StartActivityEvent {
    protected Started(BuildTarget buildTarget, String activityName) {
      super(buildTarget, activityName);
    }

    @Override
    public String getEventName() {
      return "StartActivityStarted";
    }
  }

  public static class Finished extends StartActivityEvent {
    private final boolean success;

    protected Finished(BuildTarget buildTarget, String activityName, boolean success) {
      super(buildTarget, activityName);
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

      Finished that = (Finished) o;
      return isSuccess() == that.isSuccess();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getActivityName(), getBuildTarget(), isSuccess());
    }
  }
}
