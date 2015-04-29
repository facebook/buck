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
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

public class ProjectConfigDescription implements Description<ProjectConfigDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("project_config");

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
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    return new ProjectConfig(
        params,
        new SourcePathResolver(resolver),
        args.srcTarget.transform(resolver.getRuleFunction()).orNull(),
        args.srcRoots.orNull(),
        args.srcResourceRoots.orNull(),
        args.testTarget.transform(resolver.getRuleFunction()).orNull(),
        args.testRoots.orNull(),
        args.testResourceRoots.orNull(),
        args.isIntellijPlugin.or(false));
  }

  @TargetName(name = "project_config")
  @SuppressFieldNotInitialized
    public static class Arg {
    public Optional<BuildTarget> srcTarget;
    public Optional<ImmutableList<String>> srcRoots;
    public Optional<ImmutableList<String>> srcResourceRoots;
    public Optional<BuildTarget> testTarget;
    public Optional<ImmutableList<String>> testRoots;
    public Optional<ImmutableList<String>> testResourceRoots;
    public Optional<Boolean> isIntellijPlugin;
  }
}
