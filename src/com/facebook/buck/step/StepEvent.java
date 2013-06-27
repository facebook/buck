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

package com.facebook.buck.step;

import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Base class for events about steps.
 */
public abstract class StepEvent extends BuckEvent {
  private final Step step;
  private final String shortName;
  private final String description;

  protected StepEvent(Step step, String shortName, String description) {
    this.step = Preconditions.checkNotNull(step);
    this.shortName = Preconditions.checkNotNull(shortName);
    this.description = Preconditions.checkNotNull(description);
  }

  public Step getStep() {
    return step;
  }

  public String getShortName() {
    return shortName;
  }

  public String getDescription() {
    return description;
  }

  @Override
  protected String getValueString() {
    return getShortName();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof StepEvent)) {
      return false;
    }

    StepEvent that = (StepEvent)o;

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getStep(), that.getStep());
  }

  @Override
  public int hashCode() {
    return step.hashCode();
  }


  public static Started started(Step step, String shortName, String description) {
    return new Started(step, shortName, description);
  }

  public static Finished finished(Step step, String shortName, String description, int exitCode) {
    return new Finished(step, shortName, description, exitCode);
  }

  public static class Started extends StepEvent {
    protected Started(Step step, String shortName, String description) {
      super(step, shortName, description);
    }

    @Override
    protected String getEventName() {
      return "StepStarted";
    }
  }

  public static class Finished extends StepEvent {
    private final int exitCode;

    protected Finished(Step step, String shortName, String description, int exitCode) {
      super(step, shortName, description);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    protected String getEventName() {
      return "StepFinished";
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished) o;
      return that.exitCode == getExitCode();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getStep(), getExitCode());
    }
  }
}
