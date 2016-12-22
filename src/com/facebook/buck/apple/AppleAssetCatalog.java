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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class AppleAssetCatalog extends AbstractBuildRule {

  public static final Flavor FLAVOR = ImmutableFlavor.of("apple-asset-catalog");

  private static final String BUNDLE_DIRECTORY_EXTENSION = ".bundle";
  private static final String XCASSETS_DIRECTORY_EXTENSION = ".xcassets";

  @AddToRuleKey
  private final String applePlatformName;

  @AddToRuleKey
  private final Tool actool;

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> assetCatalogDirs;

  @AddToRuleKey(stringify = true)
  private final Path outputDir;

  private final Path outputPlist;

  @AddToRuleKey
  private final Optional<String> appIcon;

  @AddToRuleKey
  private final Optional<String> launchImage;

  @AddToRuleKey
  private final AppleAssetCatalogDescription.Optimization optimization;

  AppleAssetCatalog(
      BuildRuleParams params,
      final SourcePathResolver resolver,
      String applePlatformName,
      Tool actool,
      SortedSet<SourcePath> assetCatalogDirs,
      Optional<String> appIcon,
      Optional<String> launchImage,
      AppleAssetCatalogDescription.Optimization optimization,
      String bundleName) {
    super(params, resolver);
    Preconditions.checkArgument(
        Iterables.all(
            assetCatalogDirs,
            input -> resolver.getAbsolutePath(input)
                .toString()
                .endsWith(XCASSETS_DIRECTORY_EXTENSION)));
    this.applePlatformName = applePlatformName;
    this.actool = actool;
    this.assetCatalogDirs = ImmutableSortedSet.copyOf(assetCatalogDirs);
    this.outputDir = BuildTargets.getGenPath(getProjectFilesystem(), params.getBuildTarget(), "%s")
        .resolve(bundleName + BUNDLE_DIRECTORY_EXTENSION);
    this.outputPlist = BuildTargets.getScratchPath(
        getProjectFilesystem(),
        params.getBuildTarget(),
        "%s-output.plist");
    this.appIcon = appIcon;
    this.launchImage = launchImage;
    this.optimization = optimization;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    stepsBuilder.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDir));
    stepsBuilder.add(new MkdirStep(getProjectFilesystem(), outputPlist.getParent()));
    ImmutableSortedSet<Path> absoluteAssetCatalogDirs =
        getResolver().getAllAbsolutePaths(assetCatalogDirs);
    stepsBuilder.add(
        new ActoolStep(
            getProjectFilesystem().getRootPath(),
            applePlatformName,
            actool.getEnvironment(),
            actool.getCommandPrefix(getResolver()),
            absoluteAssetCatalogDirs,
            getProjectFilesystem().resolve(outputDir),
            getProjectFilesystem().resolve(outputPlist),
            appIcon,
            launchImage,
            optimization));

    buildableContext.recordArtifact(getOutputDir());
    buildableContext.recordArtifact(outputPlist);
    return stepsBuilder.build();
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

  public Path getOutputDir() {
    return outputDir;
  }

  public Path getOutputPlist() {
    return outputPlist;
  }
}
