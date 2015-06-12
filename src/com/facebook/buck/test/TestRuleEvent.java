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

package com.facebook.buck.test;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.model.BuildTarget;

import java.util.Objects;

/**
 * Base class for events about test rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class TestRuleEvent extends AbstractBuckEvent {
  private final BuildTarget buildTarget;

  protected TestRuleEvent(BuildTarget buildTarget) {
    this.buildTarget = buildTarget;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String getValueString() {
    return buildTarget.toString();
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof TestRuleEvent)) {
      return false;
    }

    return Objects.equals(getBuildTarget(), ((TestRuleEvent) event).getBuildTarget());
  }

  @Override
  public int hashCode() {
    return Objects.hash(buildTarget);
  }

  public static Started started(BuildTarget buildTarget) {
    return new Started(buildTarget);
  }

  public static Finished finished(BuildTarget buildTarget) {
    return new Finished(buildTarget);
  }

  public static class Started extends TestRuleEvent {
    protected Started(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public String getEventName() {
      return "TestRuleStarted";
    }
  }

  public static class Finished extends TestRuleEvent {

    protected Finished(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public String getEventName() {
      return "TestRuleFinished";
    }
  }
}
