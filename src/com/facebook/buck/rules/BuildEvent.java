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
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

/**
 * Base class for events about building.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class BuildEvent extends AbstractBuckEvent {

  public static Started started(Iterable<String> buildArgs) {
    return new Started(ImmutableSet.copyOf(buildArgs));
  }

  public static Finished finished(Iterable<String> buildArgs, int exitCode) {
    return new Finished(ImmutableSet.copyOf(buildArgs), exitCode);
  }

  public static RuleCountCalculated ruleCountCalculated(
      ImmutableSet<BuildTarget> buildTargets,
      int ruleCount) {
    return new RuleCountCalculated(buildTargets, ruleCount);
  }

  public static class Started extends BuildEvent {

    private final ImmutableSet<String> buildArgs;

    protected Started(ImmutableSet<String> buildArgs) {
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

    @Override
    public int hashCode() {
      return Objects.hashCode(buildArgs);
    }

    @Override
    public boolean isRelatedTo(BuckEvent event) {
      if (!(event instanceof Started)) {
        return false;
      }
      Started that = (Started) event;
      return Objects.equal(buildArgs, that.buildArgs);
    }

    public ImmutableSet<String> getBuildArgs() {
      return buildArgs;
    }

  }

  public static class Finished extends BuildEvent {

    private final ImmutableSet<String> buildArgs;
    private final int exitCode;

    protected Finished(ImmutableSet<String> buildArgs, int exitCode) {
      this.buildArgs = buildArgs;
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
    public boolean isRelatedTo(BuckEvent event) {
      if (!(event instanceof Finished)) {
        return false;
      }
      Finished that = (Finished) event;
      return Objects.equal(exitCode, that.exitCode);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(exitCode);
    }

  }

  public static class RuleCountCalculated extends BuildEvent {

    private final ImmutableSet<BuildTarget> buildRules;
    private final int numRules;

    protected RuleCountCalculated(ImmutableSet<BuildTarget> buildRules, int numRulesToBuild) {
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
    public boolean isRelatedTo(BuckEvent event) {
      if (!(event instanceof RuleCountCalculated)) {
        return false;
      }
      RuleCountCalculated that = (RuleCountCalculated) event;
      return
          Objects.equal(buildRules, that.buildRules) &&
          Objects.equal(numRules, that.numRules);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(buildRules, numRules);
    }

  }

}
