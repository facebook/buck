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
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

/** Base class for events about building. */
public abstract class BuildEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  public BuildEvent(EventKey eventKey) {
    super(eventKey);
  }

  public static Started started(Iterable<String> buildArgs) {
    return new Started(ImmutableSet.copyOf(buildArgs));
  }

  public static Finished finished(Started started, ExitCode exitCode) {
    return new Finished(started, exitCode);
  }

  public static DistBuildStarted distBuildStarted() {
    return new DistBuildStarted();
  }

  public static DistBuildFinished distBuildFinished(DistBuildStarted started, ExitCode exitCode) {
    return new DistBuildFinished(started, exitCode);
  }

  public static RuleCountCalculated ruleCountCalculated(
      ImmutableSet<BuildTarget> buildTargets, int ruleCount) {
    return new RuleCountCalculated(buildTargets, ruleCount);
  }

  public static UnskippedRuleCountUpdated unskippedRuleCountUpdated(int ruleCount) {
    return new UnskippedRuleCountUpdated(ruleCount);
  }

  public static class Started extends BuildEvent {

    private final ImmutableSet<String> buildArgs;

    protected Started(ImmutableSet<String> buildArgs) {
      super(EventKey.unique());
      this.buildArgs = buildArgs;
    }

    @Override
    public String getEventName() {
      return BUILD_STARTED;
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
    private final ExitCode exitCode;

    protected Finished(Started started, ExitCode exitCode) {
      super(started.getEventKey());
      this.buildArgs = started.getBuildArgs();
      this.exitCode = exitCode;
    }

    public ImmutableSet<String> getBuildArgs() {
      return buildArgs;
    }

    public ExitCode getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return BUILD_FINISHED;
    }

    @Override
    protected String getValueString() {
      return String.format("exit code: %d", exitCode.getCode());
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

  public static class DistBuildStarted extends BuildEvent {

    protected DistBuildStarted() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return DIST_BUILD_STARTED;
    }

    @Override
    protected String getValueString() {
      return "";
    }
  }

  public static class DistBuildFinished extends BuildEvent {

    private final ExitCode exitCode;

    protected DistBuildFinished(DistBuildStarted started, ExitCode exitCode) {
      super(started.getEventKey());
      this.exitCode = exitCode;
    }

    public ExitCode getExitCode() {
      return exitCode;
    }

    @Override
    public String getEventName() {
      return DIST_BUILD_FINISHED;
    }

    @Override
    protected String getValueString() {
      return String.format("exit code: %d", exitCode.getCode());
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
      return Objects.hashCode(super.hashCode(), exitCode);
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

  public static class UnskippedRuleCountUpdated extends BuildEvent {

    private final int numRules;

    protected UnskippedRuleCountUpdated(int numRulesToBuild) {
      super(EventKey.unique());
      this.numRules = numRulesToBuild;
    }

    public int getNumRules() {
      return numRules;
    }

    @Override
    public String getEventName() {
      return "UnskippedRuleCountUpdated";
    }

    @Override
    protected String getValueString() {
      return Integer.toString(numRules);
    }

    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }
}
