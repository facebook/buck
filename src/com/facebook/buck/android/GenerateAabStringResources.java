/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.resources.strings.StringResourcesUtils;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;
import javax.annotation.Nullable;

/**
 * Move filtered non-English string resources (values-es/strings.xml) files to output directory.
 * These will be used by i18n aab language pack preparations.
 */
public class GenerateAabStringResources extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableList<SourcePath> filteredResources;

  private final ImmutableList<FilteredResourcesProvider> filteredResourcesProviders;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;

  protected GenerateAabStringResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<FilteredResourcesProvider> filteredResourcesProviders) {
    super(buildTarget, projectFilesystem);
    this.projectFilesystem = projectFilesystem;
    this.ruleFinder = ruleFinder;
    this.filteredResourcesProviders = filteredResourcesProviders;
    this.filteredResources =
        filteredResourcesProviders.stream()
            .flatMap(provider -> provider.getResDirectories(true).stream())
            .collect(ImmutableList.toImmutableList());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return BuildableSupport.deriveDeps(this, ruleFinder)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    // Make sure we have a clean output directory
    RelPath outputDirPath = getPathForStringResourcesDirectory();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), outputDirPath)));
    // Copy all `values-xx/strings.xml` files from resource directories to hex-enumerated resource
    // directories under output directory, retaining the input order
    steps.add(
        new AbstractExecutionStep("copy_aab_string_resources") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            ProjectFilesystem fileSystem = getProjectFilesystem();
            ImmutableList<Path> resDirs =
                filteredResourcesProviders.stream()
                    .flatMap(
                        provider ->
                            provider
                                .getRelativeResDirectories(
                                    fileSystem, buildContext.getSourcePathResolver(), true)
                                .stream())
                    .collect(ImmutableList.toImmutableList());
            StringResourcesUtils.copyAabStringResources(
                fileSystem.getRootPath(), resDirs, projectFilesystem, outputDirPath.getPath());
            return StepExecutionResults.SUCCESS;
          }
        });
    // Cache the outputDirPath with all the required string resources
    buildableContext.recordArtifact(outputDirPath.getPath());
    return steps.build();
  }

  private RelPath getPathForStringResourcesDirectory() {
    return BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "__%s__");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathForStringResourcesDirectory());
  }
}
