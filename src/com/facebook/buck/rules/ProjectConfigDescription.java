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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

public class ProjectConfigDescription implements Description<ProjectConfigDescription.Arg> {
  public static final BuildRuleType TYPE = new BuildRuleType("project_config");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> ProjectConfig createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new ProjectConfig(
        params,
        args.srcTarget.orNull(),
        args.srcRoots.orNull(),
        args.testTarget.orNull(),
        args.testRoots.orNull(),
        args.isIntellijPlugin.or(false));
  }

  @TargetName(name = "project_config")
  public static class Arg implements ConstructorArg {
    public Optional<BuildRule> srcTarget;
    public Optional<ImmutableList<String>> srcRoots;
    public Optional<BuildRule> testTarget;
    public Optional<ImmutableList<String>> testRoots;
    public Optional<Boolean> isIntellijPlugin;
  }
}
