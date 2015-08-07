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

package com.facebook.buck.parser;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.model.BuildTarget;

@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class GetTargetDependenciesEvent extends AbstractBuckEvent {
  private final BuildTarget buildTarget;

  protected GetTargetDependenciesEvent(BuildTarget buildTarget) {
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
  public int hashCode() {
    return buildTarget.hashCode();
  }

  public static Started started(BuildTarget buildTarget) {
    return new Started(buildTarget);
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static class Started extends GetTargetDependenciesEvent {
    protected Started(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public String getEventName() {
      return "GetTargetDependenciesStarted";
    }
  }

  public static class Finished extends GetTargetDependenciesEvent {
    protected Finished(Started started) {
      super(started.getBuildTarget());
      chain(started);
    }

    @Override
    public String getEventName() {
      return "GetTargetDependenciesFinished";
    }
  }
}
