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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Base class for events about building.
 */
public abstract class BuildEvent extends BuckEvent {
  private final ImmutableList<BuildTarget> buildTargets;

  /**
   * @param buildTargets The list of {@link BuildTarget}s being built.
   */
  protected BuildEvent(List<BuildTarget> buildTargets) {
    this.buildTargets = ImmutableList.copyOf(buildTargets);
  }

  public ImmutableList<BuildTarget> getBuildTargets() {
    return buildTargets;
  }

  public static Started started(List<BuildTarget> buildTargets) {
    return new Started(buildTargets);
  }

  public static Finished finished(List<BuildTarget> buildRules, int exitCode) {
    return new Finished(buildRules, exitCode);
  }

  @Override
  protected String getValueString() {
    return Joiner.on(", ").join(buildTargets);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildEvent)) {
      return false;
    }

    BuildEvent that = (BuildEvent)o;

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getBuildTargets(), that.getBuildTargets());
  }

  @Override
  public int hashCode() {
    return buildTargets.hashCode();
  }

  public static class Started extends BuildEvent {
    protected Started(List<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    protected String getEventName() {
      return "BuildStarted";
    }
  }

  public static class Finished extends BuildEvent {
    private final int exitCode;

    protected Finished(List<BuildTarget> buildRules, int exitCode) {
      super(buildRules);
      this.exitCode = exitCode;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    protected String getEventName() {
      return "BuildFinished";
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
      return Objects.hashCode(getBuildTargets(), getExitCode());
    }
  }
}
