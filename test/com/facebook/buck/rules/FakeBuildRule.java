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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class FakeBuildRule extends AbstractBuildRuleWithResolver implements BuildRule {

  @Nullable private Path outputFile;

  public FakeBuildRule(
      BuildTarget target, SourcePathResolver resolver, ImmutableSortedSet<BuildRule> deps) {
    this(new FakeBuildRuleParamsBuilder(target).setDeclaredDeps(deps).build(), resolver);
  }

  public FakeBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    super(buildRuleParams, resolver);
  }

  public FakeBuildRule(BuildTarget buildTarget, SourcePathResolver resolver) {
    this(new FakeBuildRuleParamsBuilder(buildTarget).build(), resolver);
  }

  public FakeBuildRule(
      BuildTarget target,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      BuildRule... deps) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(filesystem)
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(),
        resolver);
  }

  public FakeBuildRule(String target, SourcePathResolver resolver, BuildRule... deps) {
    this(BuildTargetFactory.newInstance(target), new FakeProjectFilesystem(), resolver, deps);
  }

  public FakeBuildRule(String target, BuildRule... deps) {
    this(target, newSourcePathResolver(), deps);
  }

  private static SourcePathResolver newSourcePathResolver() {
    return new SourcePathResolver(
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    if (outputFile == null) {
      return null;
    }
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputFile);
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
}
