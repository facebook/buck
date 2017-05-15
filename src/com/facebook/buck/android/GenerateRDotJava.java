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

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import javax.annotation.Nullable;

public class GenerateRDotJava extends AbstractBuildRule {
  @AddToRuleKey private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  @AddToRuleKey private final SourcePath pathToRDotTxtFile;
  @AddToRuleKey private Optional<String> resourceUnionPackage;
  @AddToRuleKey private boolean shouldBuildStringSourceMap;

  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private FilteredResourcesProvider resourcesProvider;

  GenerateRDotJava(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      SourcePath pathToRDotTxtFile,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      ImmutableSortedSet<BuildRule> resourceDeps,
      FilteredResourcesProvider resourcesProvider) {
    super(
        buildRuleParams.copyAppendingExtraDeps(
            getAllDeps(ruleFinder, pathToRDotTxtFile, resourceDeps, resourcesProvider)));
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.pathToRDotTxtFile = pathToRDotTxtFile;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.resourceDeps =
        resourceDeps
            .stream()
            .map(HasAndroidResourceDeps.class::cast)
            .collect(MoreCollectors.toImmutableList());
    this.resourcesProvider = resourcesProvider;
  }

  private static ImmutableSortedSet<BuildRule> getAllDeps(
      SourcePathRuleFinder ruleFinder,
      SourcePath pathToRDotTxtFile,
      ImmutableSortedSet<BuildRule> resourceDeps,
      FilteredResourcesProvider resourcesProvider) {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    builder.addAll(ruleFinder.filterBuildRuleInputs(pathToRDotTxtFile)).addAll(resourceDeps);
    resourcesProvider.getResourceFilterRule().ifPresent(builder::add);
    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = buildContext.getSourcePathResolver();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), rDotJavaSrc));

    Path rDotTxtPath = pathResolver.getAbsolutePath(pathToRDotTxtFile);
    MergeAndroidResourcesStep mergeStep =
        MergeAndroidResourcesStep.createStepForUberRDotJava(
            getProjectFilesystem(),
            buildContext.getSourcePathResolver(),
            resourceDeps,
            rDotTxtPath,
            rDotJavaSrc,
            bannedDuplicateResourceTypes,
            resourceUnionPackage);
    steps.add(mergeStep);

    if (shouldBuildStringSourceMap) {
      // Make sure we have an output directory
      Path outputDirPath = getPathForNativeStringInfoDirectory();
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), outputDirPath));

      // Add the step that parses R.txt and all the strings.xml files, and
      // produces a JSON with android resource id's and xml paths for each string resource.
      GenStringSourceMapStep genNativeStringInfo =
          new GenStringSourceMapStep(
              getProjectFilesystem(),
              rDotTxtPath.getParent(),
              resourcesProvider.getResDirectories(),
              outputDirPath);
      steps.add(genNativeStringInfo);

      // Cache the generated strings.json file, it will be stored inside outputDirPath
      buildableContext.recordArtifact(outputDirPath);
    }

    // Ensure the generated R.txt and R.java files are also recorded.
    buildableContext.recordArtifact(rDotJavaSrc);

    return steps.build();
  }

  private Path getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget(), getProjectFilesystem());
  }

  @VisibleForTesting
  static Path getPathToGeneratedRDotJavaSrcFiles(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  private Path getPathForNativeStringInfoDirectory() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__%s_string_source_map__");
  }

  public SourcePath getSourcePathToGeneratedRDotJavaSrcFiles() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), getPathToGeneratedRDotJavaSrcFiles());
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
