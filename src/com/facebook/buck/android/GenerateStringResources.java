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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
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
 * Copy filtered string resources (values/strings.xml) files to output directory. These will be used
 * by i18n to map resource_id to fbt_hash with resource_name as the intermediary
 */
public class GenerateStringResources extends AbstractBuildRule {
  @AddToRuleKey private final ImmutableList<SourcePath> filteredResources;

  private final ImmutableList<FilteredResourcesProvider> filteredResourcesProviders;
  private final SourcePathRuleFinder ruleFinder;

  private static final String VALUES = "values";
  private static final String STRINGS_XML = "strings.xml";
  private static final String NEW_RES_DIR_FORMAT = "%04x";
  // "4 digit hex" => 65536 files
  // we currently have around 1000 "values/strings.xml" files in fb4a

  protected GenerateStringResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<FilteredResourcesProvider> filteredResourcesProviders) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.filteredResourcesProviders = filteredResourcesProviders;
    this.filteredResources =
        filteredResourcesProviders
            .stream()
            .flatMap(provider -> provider.getResDirectories().stream())
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
    Path outputDirPath = getPathForStringResourcesDirectory();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), outputDirPath)));
    // Copy `values/strings.xml` files from resource directories to hex-enumerated resource
    // directories under output directory, retaining the input order
    steps.add(
        new AbstractExecutionStep("copy_string_resources") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            ProjectFilesystem fileSystem = getProjectFilesystem();
            int i = 0;
            for (Path resDir :
                filteredResourcesProviders
                    .stream()
                    .flatMap(
                        provider ->
                            provider
                                .getRelativeResDirectories(
                                    fileSystem, buildContext.getSourcePathResolver())
                                .stream())
                    .collect(ImmutableList.toImmutableList())) {
              Path stringsFilePath = resDir.resolve(VALUES).resolve(STRINGS_XML);
              if (fileSystem.exists(stringsFilePath)) {
                // create <output_dir>/<new_res_dir>/values
                Path newStringsFileDir =
                    outputDirPath.resolve(String.format(NEW_RES_DIR_FORMAT, i++)).resolve(VALUES);
                fileSystem.mkdirs(newStringsFileDir);
                // copy <res_dir>/values/strings.xml ->
                // <output_dir>/<new_res_dir>/values/strings.xml
                fileSystem.copyFile(stringsFilePath, newStringsFileDir.resolve(STRINGS_XML));
              }
            }
            return StepExecutionResults.SUCCESS;
          }
        });
    // Cache the outputDirPath with all the required string resources
    buildableContext.recordArtifact(outputDirPath);
    return steps.build();
  }

  private Path getPathForStringResourcesDirectory() {
    return BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "__%s__");
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathForStringResourcesDirectory());
  }
}
