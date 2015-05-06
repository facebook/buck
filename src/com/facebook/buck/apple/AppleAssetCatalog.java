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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.Tool;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class AppleAssetCatalog extends AbstractBuildRule {

  private static final String MERGED_FLAVOR_PREFIX = "merged-apple-asset-catalog-";
  private static final String SEPARATE_FLAVOR_PREFIX = "separate-apple-asset-catalog-";
  private static final String BUNDLE_DIRECTORY_EXTENSION = ".bundle";

  @AddToRuleKey
  private final String applePlatformName;

  @AddToRuleKey
  private final Tool actool;

  @AddToRuleKey
  private final ImmutableSortedSet<Path> assetCatalogDirs;

  @AddToRuleKey
  private final ActoolStep.BundlingMode bundlingMode;

  @AddToRuleKey(stringify = true)
  private final Path outputDir;

  AppleAssetCatalog(
      BuildRuleParams params,
      SourcePathResolver resolver,
      String applePlatformName,
      Tool actool,
      SortedSet<Path> assetCatalogDirs,
      ActoolStep.BundlingMode bundlingMode,
      String bundleName) {
    super(params, resolver);
    Preconditions.checkArgument(
        Iterables.all(
            assetCatalogDirs,
            new Predicate<Path>() {
              @Override
              public boolean apply(Path input) {
                return input.toString().endsWith(AppleDescriptions.XCASSETS_DIRECTORY_EXTENSION);
              }
            }));
    this.applePlatformName = applePlatformName;
    this.actool = actool;
    this.assetCatalogDirs = ImmutableSortedSet.copyOf(assetCatalogDirs);
    this.bundlingMode = bundlingMode;
    this.outputDir = BuildTargets.getGenPath(params.getBuildTarget(), "%s")
        .resolve(bundleName + BUNDLE_DIRECTORY_EXTENSION);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    stepsBuilder.add(new MakeCleanDirectoryStep(outputDir));

    ImmutableSortedSet<Path> absoluteAssetCatalogDirs =
        ImmutableSortedSet.copyOf(
            Iterables.transform(
                assetCatalogDirs,
                getProjectFilesystem().getAbsolutifier()));
    stepsBuilder.add(
        new ActoolStep(
            applePlatformName,
            actool.getCommandPrefix(getResolver()),
            absoluteAssetCatalogDirs,
            getProjectFilesystem().resolve(outputDir),
            bundlingMode));

    buildableContext.recordArtifactsInDirectory(getOutputDir());

    return stepsBuilder.build();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public Path getOutputDir() {
    return outputDir;
  }

  public static Flavor getFlavor(ActoolStep.BundlingMode bundlingMode, String bundleName) {
    switch (bundlingMode) {
      case MERGE_BUNDLES:
        return ImmutableFlavor.of(MERGED_FLAVOR_PREFIX + bundleName);
      case SEPARATE_BUNDLES:
        return ImmutableFlavor.of(SEPARATE_FLAVOR_PREFIX + bundleName);
    }
    throw new IllegalArgumentException("Invalid bundling mode: " + bundlingMode);
  }

}
