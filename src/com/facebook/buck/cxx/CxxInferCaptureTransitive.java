/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasPostBuildSteps;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.stream.Stream;

class CxxInferCaptureTransitive extends AbstractBuildRule
    implements HasRuntimeDeps, HasPostBuildSteps {

  private ImmutableSet<CxxInferCapture> captureRules;
  private Path outputDirectory;

  public CxxInferCaptureTransitive(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ImmutableSet<CxxInferCapture> captureRules) {
    super(target, projectFilesystem);
    this.captureRules = captureRules;
    this.outputDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), this.getBuildTarget(), "infer-%s");
  }

  public ImmutableSet<CxxInferCapture> getCaptureRules() {
    return captureRules;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return ImmutableSortedSet.copyOf(captureRules);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return captureRules.stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), outputDirectory)))
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputDirectory);
  }

  @Override
  public ImmutableList<Step> getPostBuildSteps(BuildContext context) {
    return ImmutableList.<Step>builder()
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), outputDirectory)))
        .add(
            CxxCollectAndLogInferDependenciesStep.fromCaptureOnlyRule(
                this, getProjectFilesystem(), this.outputDirectory.resolve("infer-deps.txt")))
        .build();
  }
}
