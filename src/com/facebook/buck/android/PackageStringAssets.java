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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.nio.file.Path;

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

  private static final String STRING_ASSETS_ZIP_HASH = "STRING_ASSETS_ZIP_HASH";
  @VisibleForTesting static final String STRING_ASSET_FILE_EXTENSION = ".fbstr";
  public static final String STRING_ASSETS_DIR_FORMAT = "__strings_%s__";

  private final FilteredResourcesProvider filteredResourcesProvider;
  private final SourcePath rDotTxtPath;
  private final ImmutableSet<String> locales;

  public PackageStringAssets(
      BuildRuleParams params,
      ImmutableSet<String> locales,
      FilteredResourcesProvider filteredResourcesProvider,
      SourcePath rDotTxtPath) {
    super(params);
    this.locales = locales;
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.rDotTxtPath = rDotTxtPath;
  }

  // TODO(russell): Add an integration test for packaging string assets
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    if (filteredResourcesProvider.getResDirectories().isEmpty()) {
      // There is no zip file, but we still need to provide a consistent hash to
      // ComputeExopackageDepsAbi in this case.
      buildableContext.addMetadata(STRING_ASSETS_ZIP_HASH, Hashing.sha1().hashInt(0).toString());
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    // We need to generate a zip file with the following dir structure:
    // /assets/strings/*.fbstr
    Path pathToBaseDir = getPathToStringAssetsDir();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToBaseDir));
    Path pathToDirContainingAssetsDir = pathToBaseDir.resolve("string_assets");
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToDirContainingAssetsDir));
    final Path pathToStrings = pathToDirContainingAssetsDir.resolve("assets").resolve("strings");
    Function<String, Path> assetPathBuilder =
        locale -> pathToStrings.resolve(locale + STRING_ASSET_FILE_EXTENSION);
    Path pathToStringAssetsZip = getPathToStringAssetsZip();
    Path pathToAllLocalesStringAssetsZip = getPathToAllLocalesStringAssetsZip();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToStrings));
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
            ZipCompressionLevel.MAX_COMPRESSION_LEVEL,
            pathToDirContainingAssetsDir));
    steps.add(
        new ZipStep(
            getProjectFilesystem(),
            pathToStringAssetsZip,
            locales.stream().map(assetPathBuilder::apply).collect(MoreCollectors.toImmutableSet()),
            false,
            ZipCompressionLevel.MAX_COMPRESSION_LEVEL,
            pathToDirContainingAssetsDir));
    steps.add(
        new RecordFileSha1Step(
            getProjectFilesystem(),
            pathToStringAssetsZip,
            STRING_ASSETS_ZIP_HASH,
            buildableContext));

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
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToStringAssetsDir());
  }

  private Path getPathToStringAssetsDir() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), STRING_ASSETS_DIR_FORMAT);
  }

  public SourcePath getSourcePathToStringAssetsZip() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToStringAssetsZip());
  }
}
