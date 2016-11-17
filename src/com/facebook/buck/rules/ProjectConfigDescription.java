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
import com.google.common.collect.ImmutableList;

import java.util.Optional;

public class ProjectConfigDescription implements Description<ProjectConfigDescription.Arg> {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> ProjectConfig createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    return new ProjectConfig(
        params,
        new SourcePathResolver(resolver),
        args.srcTarget.map(resolver::getRule).orElse(null),
        args.srcRoots,
        args.testTarget.map(resolver::getRule).orElse(null),
        args.testRoots,
        args.isIntellijPlugin.orElse(false));
  }

  @TargetName(name = "project_config")
  @SuppressFieldNotInitialized
    public static class Arg extends AbstractDescriptionArg {
    public Optional<BuildTarget> srcTarget;
    public ImmutableList<String> srcRoots = ImmutableList.of();
    public Optional<BuildTarget> testTarget;
    public ImmutableList<String> testRoots = ImmutableList.of();
    public Optional<Boolean> isIntellijPlugin;
  }
}
