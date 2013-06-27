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
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

/**
 * Base class for events about building.
 */
public abstract class BuildEvent extends BuckEvent {
  private final ImmutableSet<BuildRule> buildRules;

  protected BuildEvent(Set<BuildRule> buildRules) {
    this.buildRules = ImmutableSet.copyOf(buildRules);
  }

  public ImmutableSet<BuildRule> getBuildRules() {
    return buildRules;
  }

  public static Started started(Set<BuildRule> rulesToBuild) {
    return new Started(rulesToBuild);
  }

  public static Finished finished(Set<BuildRule> buildRules, int exitCode) {
    return new Finished(buildRules, exitCode);
  }

  @Override
  protected String getValueString() {
    return Joiner.on(", ").join(buildRules);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildEvent)) {
      return false;
    }

    BuildEvent that = (BuildEvent)o;

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getBuildRules(), that.getBuildRules());
  }

  @Override
  public int hashCode() {
    return buildRules.hashCode();
  }

  public static class Started extends BuildEvent {
    protected Started(Set<BuildRule> buildRules) {
      super(buildRules);
    }

    @Override
    protected String getEventName() {
      return "BuildStarted";
    }
  }

  public static class Finished extends BuildEvent {
    private final int exitCode;

    protected Finished(Set<BuildRule> buildRules, int exitCode) {
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
      return Objects.hashCode(getBuildRules(), getExitCode());
    }
  }
}
