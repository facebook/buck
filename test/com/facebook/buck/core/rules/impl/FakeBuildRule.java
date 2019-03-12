/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class FakeBuildRule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasRuntimeDeps {

  @Nullable private Path outputFile;
  private Set<BuildRule> runtimeDeps = new HashSet<BuildRule>();
  private BuildRuleResolver ruleResolver; // real BuildRules shouldn't hold this in a field

  public FakeBuildRule(BuildTarget target, ImmutableSortedSet<BuildRule> deps) {
    this(target, new FakeProjectFilesystem(), TestBuildRuleParams.create().withDeclaredDeps(deps));
  }

  public FakeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams) {
    super(buildTarget, projectFilesystem, buildRuleParams);
  }

  public FakeBuildRule(BuildTarget buildTarget) {
    this(buildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
  }

  public FakeBuildRule(BuildTarget target, ProjectFilesystem filesystem, BuildRule... deps) {
    this(
        target,
        filesystem,
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.copyOf(deps)));
  }

  public FakeBuildRule(BuildTarget target, BuildRule... deps) {
    this(target, new FakeProjectFilesystem(), deps);
  }

  public FakeBuildRule(String target, BuildRule... deps) {
    this(BuildTargetFactory.newInstance(target), new FakeProjectFilesystem(), deps);
  }

  public FakeBuildRule(String target, ProjectFilesystem filesystem, BuildRule... deps) {
    this(BuildTargetFactory.newInstance(target), filesystem, deps);
  }

  public FakeBuildRule setRuntimeDeps(BuildRule... deps) {
    runtimeDeps = Sets.newHashSet(deps);
    return this;
  }

  public BuildRuleResolver getRuleResolver() {
    return ruleResolver;
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    if (outputFile == null) {
      return null;
    }
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputFile);
  }

  public FakeBuildRule setOutputFile(@Nullable String outputFile) {
    this.outputFile = outputFile == null ? null : Paths.get(outputFile);
    return this;
  }

  @Nullable
  public Path getOutputFile() {
    return outputFile;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return runtimeDeps.stream().map(x -> x.getBuildTarget());
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder) {
    this.ruleResolver = ruleResolver;
  }
}
