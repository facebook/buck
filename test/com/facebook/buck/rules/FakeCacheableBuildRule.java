/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.stream.Stream;

public class FakeCacheableBuildRule extends FakeBuildRule
    implements CacheableBuildRule, HasRuntimeDeps {
  private BuildRuleResolver ruleResolver;
  private SortedSet<BuildTarget> runtimeDeps;

  public FakeCacheableBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams) {
    super(buildTarget, projectFilesystem, buildRuleParams);
  }

  public FakeCacheableBuildRule(String target, BuildRule... deps) {
    this(target, ImmutableSortedSet.of(), deps);
  }

  public FakeCacheableBuildRule(
      String target, SortedSet<BuildTarget> runtimeDeps, BuildRule... deps) {
    super("//test:" + target, deps);
    this.runtimeDeps = runtimeDeps;
  }

  public BuildRuleResolver getRuleResolver() {
    return ruleResolver;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return (runtimeDeps != null) ? runtimeDeps.stream() : Stream.of();
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {
    this.ruleResolver = ruleResolver;
  }
}
