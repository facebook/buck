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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nullable;

public class ProjectConfigBuilder {

  private ProjectConfigBuilder() {
    // Utility class
  }

  public static Builder newProjectConfigRuleBuilder() {
    return new Builder();
  }

  public static class Builder {

    private BuildTarget buildTarget;
    @Nullable private BuildRule srcRule = null;
    @Nullable private ImmutableList<String> srcRoots = null;
    @Nullable private BuildRule testRule = null;
    @Nullable private ImmutableList<String> testRoots = null;
    boolean isIntellijProject = false;

    public Builder setBuildTarget (BuildTarget target) {
      this.buildTarget = Preconditions.checkNotNull(target);
      return this;
    }

    public Builder setSrcRule(@Nullable BuildRule srcRule) {
      this.srcRule = srcRule;
      return this;
    }

    public Builder setSrcRoots(@Nullable ImmutableList<String> srcRoots) {
      this.srcRoots = srcRoots;
      return this;
    }

    public Builder setTestRule(@Nullable BuildRule testRule) {
      this.testRule = testRule;
      return this;
    }

    public Builder setTestRoots(@Nullable ImmutableList<String> testRoots) {
      this.testRoots = testRoots;
      return this;
    }

    public Builder setIntellijProject(boolean isIntellijProject) {
      this.isIntellijProject = isIntellijProject;
      return this;
    }

    public ProjectConfig buildAsBuildable() {
      return new ProjectConfig(
          buildTarget,
          srcRule,
          srcRoots,
          testRule,
          testRoots,
          isIntellijProject);
    }

    public BuildRule build() {
      return new AbstractBuildable.AnonymousBuildRule(
          ProjectConfigDescription.TYPE,
          buildAsBuildable(),
          new FakeBuildRuleParams(buildTarget));
    }
  }
}
