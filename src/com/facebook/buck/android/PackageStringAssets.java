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
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Buildable responsible for compiling non-english string resources to {@code .fbstr} files stored
 * as assets. Only applicable for {@code android_binary} rules with {@code resource_compression}
 * parameter set to {@link com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode
 * #ENABLED_WITH_STRINGS_AS_ASSETS}.
 */
public class PackageStringAssets extends AbstractBuildRule
    implements InitializableFromDisk<PackageStringAssets.BuildOutput> {

  private static final String STRING_ASSETS_ZIP_HASH = "STRING_ASSETS_ZIP_HASH";

  private final FilteredResourcesProvider filteredResourcesProvider;
  private final AaptPackageResources aaptPackageResources;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public PackageStringAssets(
      BuildRuleParams params,
      SourcePathResolver resolver,
      FilteredResourcesProvider filteredResourcesProvider,
      AaptPackageResources aaptPackageResources) {
    super(params, resolver);
    this.filteredResourcesProvider = filteredResourcesProvider;
    this.aaptPackageResources = aaptPackageResources;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    if (filteredResourcesProvider.getResDirectories().isEmpty()) {
      // There is no zip file, but we still need to provide a consistent hash to
      // ComputeExopackageDepsAbi in this case.
      buildableContext.addMetadata(
          STRING_ASSETS_ZIP_HASH,
          Hashing.sha1().hashInt(0).toString());
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    // We need to generate a zip file with the following dir structure:
    // /assets/strings/*.fbstr
    Path pathToBaseDir = getPathToStringAssetsDir();
    steps.add(new MakeCleanDirectoryStep(pathToBaseDir));
    Path pathToDirContainingAssetsDir = pathToBaseDir.resolve("string_assets");
    steps.add(new MakeCleanDirectoryStep(pathToDirContainingAssetsDir));
    Path pathToStrings = pathToDirContainingAssetsDir.resolve("assets").resolve("strings");
    Path pathToStringAssetsZip = getPathToStringAssetsZip();
    steps.add(new MakeCleanDirectoryStep(pathToStrings));
    steps.add(new CompileStringsStep(
            filteredResourcesProvider.getNonEnglishStringFiles(),
            aaptPackageResources.getPathToRDotTxtDir(),
            pathToStrings));
    steps.add(new ZipStep(
            pathToStringAssetsZip,
            ImmutableSet.<Path>of(),
            false,
            ZipStep.MAX_COMPRESSION_LEVEL,
            pathToDirContainingAssetsDir));
    steps.add(
        new RecordFileSha1Step(pathToStringAssetsZip, STRING_ASSETS_ZIP_HASH, buildableContext));
    buildableContext.recordArtifact(pathToStringAssetsZip);
    return steps.build();
  }

  public Path getPathToStringAssetsZip() {
    return getPathToStringAssetsDir().resolve("string_assets.zip");
  }

  public Sha1HashCode getStringAssetsZipHash() {
    return buildOutputInitializer.getBuildOutput().stringAssetsZipHash;
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return new BuildOutput(onDiskBuildInfo.getHash(STRING_ASSETS_ZIP_HASH).get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  public static class BuildOutput {
    private final Sha1HashCode stringAssetsZipHash;

    public BuildOutput(Sha1HashCode stringAssetsZipHash) {
      this.stringAssetsZipHash = stringAssetsZipHash;
    }
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  private Path getPathToStringAssetsDir() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__strings_%s__");
  }
}
