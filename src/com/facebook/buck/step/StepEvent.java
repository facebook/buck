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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.google.common.base.Objects;

/**
 * Base class for events about steps.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class StepEvent extends AbstractBuckEvent implements LeafEvent {
  private final Step step;
  private final String description;

  protected StepEvent(Step step, String description) {
    this.step = step;
    this.description = description;
  }

  public Step getStep() {
    return step;
  }

  public String getDescription() {
    return description;
  }

  @Override
  public String getCategory() {
    return getStep().getShortName();
  }

  @Override
  protected String getValueString() {
    return getStep().getShortName();
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof StepEvent)) {
      return false;
    }

    StepEvent that = (StepEvent) event;

    return Objects.equal(getStep(), that.getStep());
  }

  @Override
  public int hashCode() {
    return step.hashCode();
  }


  public static Started started(Step step, String description) {
    return new Started(step, description);
  }

  public static Finished finished(Step step, String description, int exitCode) {
    return new Finished(step, description, exitCode);
  }

  public static class Started extends StepEvent {
    protected Started(Step step, String description) {
      super(step, description);
    }

    @Override
    public String getEventName() {
      return "StepStarted";
    }
  }

  public static class Finished extends StepEvent {
    private final int exitCode;

    protected Finished(Step step, String description, int exitCode) {
      super(step, description);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
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
