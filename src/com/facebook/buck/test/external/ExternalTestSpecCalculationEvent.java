/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.test.external;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Base class for events about external test specs. */
public abstract class ExternalTestSpecCalculationEvent extends AbstractBuckEvent
    implements WorkAdvanceEvent {
  private final BuildTarget buildTarget;

  ExternalTestSpecCalculationEvent(BuildTarget buildTarget) {
    super(
        EventKey.slowValueKey(
            ExternalTestSpecCalculationEvent.class.getSimpleName(),
            buildTarget.getFullyQualifiedName()));
    this.buildTarget = buildTarget;
  }

  @JsonIgnore
  public String getCategory() {
    return "external_test_spec_calc";
  }

  @Override
  public String getValueString() {
    return buildTarget.toString();
  }

  @JsonIgnore
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public static Started started(BuildTarget buildTarget) {
    return new Started(buildTarget);
  }

  public static Finished finished(BuildTarget buildTarget) {
    return new Finished(buildTarget);
  }

  public static class Started extends ExternalTestSpecCalculationEvent {
    protected Started(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public String getEventName() {
      return "ExternalTestSpecCalculationStarted";
    }
  }

  public static class Finished extends ExternalTestSpecCalculationEvent {

    protected Finished(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public String getEventName() {
      return "ExternalTestSpecCalculationFinished";
    }
  }
}
