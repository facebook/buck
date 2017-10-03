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
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
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
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class GenerateStringSourceMap extends AbstractBuildRule implements HasRuntimeDeps {
  @AddToRuleKey private final SourcePath pathToRDotTxtFile;
  @AddToRuleKey private final ImmutableList<SourcePath> filteredResources;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final SourcePathRuleFinder ruleFinder;

  protected GenerateStringSourceMap(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToRDotTxtFile,
      FilteredResourcesProvider filteredResourcesProvider) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.pathToRDotTxtFile = pathToRDotTxtFile;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.filteredResources = filteredResourcesProvider.getResDirectories();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return BuildableSupport.deriveDeps(this, ruleFinder)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    // Make sure we have an output directory
    Path outputDirPath = getPathForNativeStringInfoDirectory();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), outputDirPath)));
    // Add the step that parses R.txt and all the strings.xml files, and
    // produces a JSON with android resource id's and xml paths for each string resource.
    GenStringSourceMapStep genNativeStringInfo =
        new GenStringSourceMapStep(
            getProjectFilesystem(),
            buildContext.getSourcePathResolver().getAbsolutePath(pathToRDotTxtFile),
            filteredResourcesProvider.getRelativeResDirectories(
                getProjectFilesystem(), buildContext.getSourcePathResolver()),
            outputDirPath);
    steps.add(genNativeStringInfo);
    // Cache the generated strings.json file, it will be stored inside outputDirPath
    buildableContext.recordArtifact(outputDirPath);
    return steps.build();
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "__%s__");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), getPathForNativeStringInfoDirectory());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder
        .filterBuildRuleInputs(filteredResources)
        .stream()
        .map(BuildRule::getBuildTarget);
  }
}
