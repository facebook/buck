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
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class GenerateRDotJava extends AbstractBuildRule {
  @AddToRuleKey private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  @AddToRuleKey private final ImmutableCollection<SourcePath> pathToRDotTxtFiles;
  @AddToRuleKey private final ImmutableCollection<SourcePath> pathToOverrideSymbolsFile;
  @AddToRuleKey private final Optional<SourcePath> duplicateResourceWhitelistPath;
  @AddToRuleKey private final Optional<String> resourceUnionPackage;

  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private final ImmutableCollection<FilteredResourcesProvider> resourcesProviders;
  // TODO(cjhopman): allResourceDeps is used for getBuildDeps(), can that just use resourceDeps?
  private final ImmutableSortedSet<BuildRule> allResourceDeps;
  private final SourcePathRuleFinder ruleFinder;

  GenerateRDotJava(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      Optional<SourcePath> duplicateResourceWhitelistPath,
      ImmutableCollection<SourcePath> pathToRDotTxtFiles,
      Optional<String> resourceUnionPackage,
      ImmutableSortedSet<BuildRule> resourceDeps,
      ImmutableCollection<FilteredResourcesProvider> resourcesProviders) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.duplicateResourceWhitelistPath = duplicateResourceWhitelistPath;
    this.pathToRDotTxtFiles = pathToRDotTxtFiles;
    this.resourceUnionPackage = resourceUnionPackage;
    this.allResourceDeps = resourceDeps;
    this.resourceDeps =
        resourceDeps
            .stream()
            .map(HasAndroidResourceDeps.class::cast)
            .collect(ImmutableList.toImmutableList());
    this.resourcesProviders = resourcesProviders;
    this.pathToOverrideSymbolsFile =
        resourcesProviders
            .stream()
            .filter(provider -> provider.getOverrideSymbolsPath().isPresent())
            .map(provider -> provider.getOverrideSymbolsPath().get())
            .collect(ImmutableList.toImmutableList());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    builder
        .addAll(
            resourcesProviders
                .stream()
                .filter(provider -> provider.getOverrideSymbolsPath().isPresent())
                .flatMap(
                    provider ->
                        ruleFinder
                            .filterBuildRuleInputs(provider.getOverrideSymbolsPath().get())
                            .stream())
                .collect(ImmutableList.toImmutableList()))
        .addAll(
            resourcesProviders
                .stream()
                .filter(provider -> provider.getResourceFilterRule().isPresent())
                .map(provider -> provider.getResourceFilterRule().get())
                .collect(ImmutableList.toImmutableList()))
        .addAll(allResourceDeps);
    pathToRDotTxtFiles
        .stream()
        .forEach(pathToRDotTxt -> builder.addAll(ruleFinder.filterBuildRuleInputs(pathToRDotTxt)));
    duplicateResourceWhitelistPath.ifPresent(
        p -> builder.addAll(ruleFinder.filterBuildRuleInputs(p)));
    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = buildContext.getSourcePathResolver();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), rDotJavaSrc)));

    MergeAndroidResourcesStep mergeStep =
        MergeAndroidResourcesStep.createStepForUberRDotJava(
            getProjectFilesystem(),
            buildContext.getSourcePathResolver(),
            resourceDeps,
            pathToRDotTxtFiles
                .stream()
                .map(p -> pathResolver.getAbsolutePath(p))
                .collect(ImmutableList.toImmutableList()),
            rDotJavaSrc,
            bannedDuplicateResourceTypes,
            duplicateResourceWhitelistPath.map(pathResolver::getAbsolutePath),
            pathToOverrideSymbolsFile
                .stream()
                .map(p -> pathResolver.getAbsolutePath(p))
                .collect(ImmutableList.toImmutableList()),
            resourceUnionPackage);
    steps.add(mergeStep);

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
    return BuildTargetPaths.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public SourcePath getSourcePathToGeneratedRDotJavaSrcFiles() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToGeneratedRDotJavaSrcFiles());
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
