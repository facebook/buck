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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;


/**
 * Base class for events about building.
 */
public abstract class BuildEvent extends AbstractBuckEvent {

  public BuildEvent(EventKey eventKey) {
    super(eventKey);
  }

  public static Started started(Iterable<String> buildArgs) {
    return new Started(ImmutableSet.copyOf(buildArgs));
  }

  public static Finished finished(Started started, int exitCode) {
    return new Finished(started, exitCode);
  }

  public static RuleCountCalculated ruleCountCalculated(
      ImmutableSet<BuildTarget> buildTargets,
      int ruleCount) {
    return new RuleCountCalculated(buildTargets, ruleCount);
  }

  public static class Started extends BuildEvent {

    private final ImmutableSet<String> buildArgs;

    protected Started(ImmutableSet<String> buildArgs) {
      super(EventKey.unique());
      this.buildArgs = buildArgs;
    }

    @Override
    public String getEventName() {
      return "BuildStarted";
    }

    @Override
    protected String getValueString() {
      return Joiner.on(", ").join(buildArgs);
    }

    public ImmutableSet<String> getBuildArgs() {
      return buildArgs;
    }
  }

  public static class Finished extends BuildEvent {

    private final ImmutableSet<String> buildArgs;
    private final int exitCode;

    protected Finished(Started started, int exitCode) {
      super(started.getEventKey());
      this.buildArgs = started.getBuildArgs();
      this.exitCode = exitCode;
    }

    public ImmutableSet<String> getBuildArgs() {
      return buildArgs;
    }

    public int getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return "BuildFinished";
    }

    @Override
    protected String getValueString() {
      return String.format("exit code: %d", exitCode);
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
      return Objects.hashCode(super.hashCode(), buildArgs, exitCode);
    }
  }

  public static class RuleCountCalculated extends BuildEvent {

    private final ImmutableSet<BuildTarget> buildRules;
    private final int numRules;

    protected RuleCountCalculated(ImmutableSet<BuildTarget> buildRules, int numRulesToBuild) {
      super(EventKey.unique());
      this.buildRules = buildRules;
      this.numRules = numRulesToBuild;
    }

    public ImmutableSet<BuildTarget> getBuildRules() {
      return buildRules;
    }

    public int getNumRules() {
      return numRules;
    }

    @Override
    public String getEventName() {
      return "RuleCountCalculated";
    }

    @Override
    protected String getValueString() {
      return Joiner.on(", ").join(buildRules);
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
      return Objects.hashCode(super.hashCode(), buildRules, numRules);
    }
  }

}
