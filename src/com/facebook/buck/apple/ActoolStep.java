/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;

class ActoolStep extends ShellStep {

  private final String applePlatformName;
  private final String targetSDKVersion;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> actoolCommand;
  private final SortedSet<Path> assetCatalogDirs;
  private final Path output;
  private final Path outputPlist;
  private final Optional<String> appIcon;
  private final Optional<String> launchImage;
  private final AppleAssetCatalogsCompilationOptions compilationOptions;

  public ActoolStep(
      BuildTarget buildTarget,
      Path workingDirectory,
      String applePlatformName,
      String targetSDKVersion,
      ImmutableMap<String, String> environment,
      List<String> actoolCommand,
      SortedSet<Path> assetCatalogDirs,
      Path output,
      Path outputPlist,
      Optional<String> appIcon,
      Optional<String> launchImage,
      AppleAssetCatalogsCompilationOptions compilationOptions) {
    super(Optional.of(buildTarget), workingDirectory);
    this.applePlatformName = applePlatformName;
    this.targetSDKVersion = targetSDKVersion;
    this.environment = environment;
    this.actoolCommand = ImmutableList.copyOf(actoolCommand);
    this.assetCatalogDirs = assetCatalogDirs;
    this.output = output;
    this.outputPlist = outputPlist;
    this.appIcon = appIcon;
    this.launchImage = launchImage;
    this.compilationOptions = compilationOptions;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();

    commandBuilder.addAll(actoolCommand);
    commandBuilder.add(
        "--platform",
        applePlatformName,
        "--minimum-deployment-target",
        targetSDKVersion,
        "--compile",
        output.toString(),
        "--output-partial-info-plist",
        outputPlist.toString());

    if (applePlatformName.equals(ApplePlatform.APPLETVOS.getName())
        || applePlatformName.equals(ApplePlatform.APPLETVSIMULATOR.getName())) {
      commandBuilder.add("--target-device", "tv");
    } else if (applePlatformName.equals(ApplePlatform.WATCHOS.getName())
        || applePlatformName.equals(ApplePlatform.WATCHSIMULATOR.getName())) {
      commandBuilder.add("--target-device", "watch");
    } else if (applePlatformName.equals(ApplePlatform.MACOSX.getName())) {
      commandBuilder.add("--target-device", "mac");
    } else {
      // TODO(jakubzika): Let apps decide which device they want to target (iPhone / iPad / both)
      commandBuilder.add(
          "--target-device", "iphone",
          "--target-device", "ipad");
    }

    if (appIcon.isPresent()) {
      commandBuilder.add("--app-icon", appIcon.get());
    }

    if (launchImage.isPresent()) {
      commandBuilder.add("--launch-image", launchImage.get());
    }

    if (compilationOptions.getNotices()) {
      commandBuilder.add("--notices");
    }
    if (compilationOptions.getWarnings()) {
      commandBuilder.add("--warnings");
    }
    if (compilationOptions.getErrors()) {
      commandBuilder.add("--errors");
    }
    if (compilationOptions.getCompressPngs()) {
      commandBuilder.add("--compress-pngs");
    }
    commandBuilder.add("--optimization", compilationOptions.getOptimization().toArgument());
    commandBuilder.add("--output-format", compilationOptions.getOutputFormat().toArgument());
    commandBuilder.addAll(compilationOptions.getExtraFlags());

    commandBuilder.addAll(Iterables.transform(assetCatalogDirs, Object::toString));

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "actool";
  }
}
