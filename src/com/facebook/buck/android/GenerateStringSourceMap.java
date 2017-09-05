/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class GenerateStringSourceMap extends AbstractBuildRule implements HasRuntimeDeps {
  private final SourcePathRuleFinder ruleFinder;
  // TODO(gvbharath): Create SourcePath fields for any input files needed
  // ...
  // @AddToRuleKey private final ImmutableList<SourcePath> resources;
  // @AddToRuleKey private final SourcePath someInput;

  protected GenerateStringSourceMap(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return BuildableSupport.deriveDeps(this, ruleFinder)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path outputDirPath = getPathForNativeStringInfoDirectory();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputDirPath)));

    // TODO(gvbharath): Construct this step. See GenerateRDotJava.
    // GenStringSourceMapStep genNativeStringInfo = ...;
    // steps.add(genNativeStringInfo);
    steps.add(
        new WriteFileStep(
            getProjectFilesystem(), "some data", outputDirPath.resolve("map.json"), false));

    // Cache the generated strings.json file, it will be stored inside outputDirPath
    buildableContext.recordArtifact(outputDirPath);
    return steps.build();
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s/string_source_map");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), getPathForNativeStringInfoDirectory());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // TODO(gvbharath): Construct list of SourcePaths that need to exist after building this rule
    // (whatever is specified in the map that we need to also access).
    ImmutableList<SourcePath> inputs = ImmutableList.of();
    return ruleFinder.filterBuildRuleInputs(inputs).stream().map(BuildRule::getBuildTarget);
  }
}
