/*
 * Copyright 2014-present Facebook, Inc.
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
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Buildable responsible for compiling non-english string resources to {@code .fbstr} files stored
 * as assets. Only applicable for {@code android_binary} rules with {@code resource_compression}
 * parameter set to {@link com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode
 * #ENABLED_WITH_STRINGS_AS_ASSETS}.
 *
 * <p>Produces an all_locales_string_assets.zip containing .fbstr files for all locales the app has
 * strings for, and a string_assets.zip file that contains the .fbstr files filtered by the set of
 * locales provided. The contents of string_assets.zip is built into the assets of the APK.
 * all_locales_string_assets.zip is used for debugging purposes.
 */
public class PackageStringAssets extends AbstractBuildRule {
  @VisibleForTesting static final String STRING_ASSET_FILE_EXTENSION = ".fbstr";
  public static final String STRING_ASSETS_DIR_FORMAT = "__strings_%s__";

  private final FilteredResourcesProvider filteredResourcesProvider;
  private final SourcePath rDotTxtPath;
  private final ImmutableSet<String> locales;

  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  PackageStringAssets(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableSet<String> locales,
      SourcePath pathToRDotTxt) {
    super(buildTarget, projectFilesystem);
    this.locales = locales;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.rDotTxtPath = pathToRDotTxt;

    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(pathToRDotTxt))
                    .addAll(resourceRules)
                    .addAll(rulesWithResourceDirectories)
                    // Model the dependency on the presence of res directories, which, in the
                    // case of resource filtering, is cached by the `ResourcesFilter` rule.
                    .addAll(
                        Iterables.filter(
                            ImmutableList.of(filteredResourcesProvider), BuildRule.class))
                    .build());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  // TODO(russell): Add an integration test for packaging string assets
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    if (filteredResourcesProvider.getResDirectories().isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    // We need to generate a zip file with the following dir structure:
    // /assets/strings/*.fbstr
    Path pathToBaseDir = getPathToStringAssetsDir();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToBaseDir)));
    Path pathToDirContainingAssetsDir = pathToBaseDir.resolve("string_assets");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                pathToDirContainingAssetsDir)));
    Path pathToStrings = pathToDirContainingAssetsDir.resolve("assets").resolve("strings");
    Function<String, Path> assetPathBuilder =
        locale -> pathToStrings.resolve(locale + STRING_ASSET_FILE_EXTENSION);
    Path pathToStringAssetsZip = getPathToStringAssetsZip();
    Path pathToAllLocalesStringAssetsZip = getPathToAllLocalesStringAssetsZip();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToStrings)));
    steps.add(
        new CompileStringsStep(
            getProjectFilesystem(),
            filteredResourcesProvider.getStringFiles(),
            context.getSourcePathResolver().getIdeallyRelativePath(rDotTxtPath),
            assetPathBuilder));
    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            pathToAllLocalesStringAssetsZip,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.MAX,
            pathToDirContainingAssetsDir));
    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            pathToStringAssetsZip,
            locales.stream().map(assetPathBuilder).collect(ImmutableSet.toImmutableSet()),
            false,
            ZipCompressionLevel.MAX,
            pathToDirContainingAssetsDir));

    buildableContext.recordArtifact(pathToAllLocalesStringAssetsZip);
    buildableContext.recordArtifact(pathToStringAssetsZip);
    return steps.build();
  }

  Path getPathToStringAssetsZip() {
    return getPathToStringAssetsDir().resolve("string_assets.zip");
  }

  private Path getPathToAllLocalesStringAssetsZip() {
    return getPathToStringAssetsDir().resolve("all_locales_string_assets.zip");
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToStringAssetsDir());
  }

  private Path getPathToStringAssetsDir() {
    return BuildTargetPaths.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), STRING_ASSETS_DIR_FORMAT);
  }

  public SourcePath getSourcePathToStringAssetsZip() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToStringAssetsZip());
  }
}
