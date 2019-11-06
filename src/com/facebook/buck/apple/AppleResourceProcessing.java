/*
 * Copyright 2019-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Contains shared logic for adding resource processing steps to apple build rules */
public class AppleResourceProcessing {
  public static final ImmutableList<String> BASE_IBTOOL_FLAGS =
      ImmutableList.of(
          "--output-format", "human-readable-text", "--notices", "--warnings", "--errors");

  private AppleResourceProcessing() {}

  /** Add Storyboard processing ibtool steps to a build rule */
  public static void addStoryboardProcessingSteps(
      SourcePathResolver resolver,
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<String> ibtoolFlags,
      boolean isLegacyWatchApp,
      ApplePlatform platform,
      ProjectFilesystem projectFilesystem,
      Logger LOG,
      Tool ibtool,
      boolean ibtoolModuleFlag,
      BuildTarget buildTarget,
      Optional<String> binaryName) {
    ImmutableList<String> modifiedFlags =
        ImmutableList.<String>builder().addAll(BASE_IBTOOL_FLAGS).addAll(ibtoolFlags).build();

    if (platform.getName().contains("watch") || isLegacyWatchApp) {
      LOG.debug(
          "Compiling storyboard %s to storyboardc %s and linking", sourcePath, destinationPath);

      Path compiledStoryboardPath =
          BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, "%s.storyboardc");

      stepsBuilder.add(
          new IbtoolStep(
              projectFilesystem,
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ibtoolModuleFlag ? binaryName : Optional.empty(),
              ImmutableList.<String>builder()
                  .addAll(modifiedFlags)
                  .add("--target-device", "watch", "--compile")
                  .build(),
              sourcePath,
              compiledStoryboardPath));

      stepsBuilder.add(
          new IbtoolStep(
              projectFilesystem,
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ibtoolModuleFlag ? binaryName : Optional.empty(),
              ImmutableList.<String>builder()
                  .addAll(modifiedFlags)
                  .add("--target-device", "watch", "--link")
                  .build(),
              compiledStoryboardPath,
              destinationPath.getParent()));

    } else {
      LOG.debug("Compiling storyboard %s to storyboardc %s", sourcePath, destinationPath);

      String compiledStoryboardFilename =
          Files.getNameWithoutExtension(destinationPath.toString()) + ".storyboardc";

      Path compiledStoryboardPath = destinationPath.getParent().resolve(compiledStoryboardFilename);

      stepsBuilder.add(
          new IbtoolStep(
              projectFilesystem,
              ibtool.getEnvironment(resolver),
              ibtool.getCommandPrefix(resolver),
              ibtoolModuleFlag ? binaryName : Optional.empty(),
              ImmutableList.<String>builder().addAll(modifiedFlags).add("--compile").build(),
              sourcePath,
              compiledStoryboardPath));
    }
  }

  /** Adds Resources processing steps to a build rule */
  public static void addResourceProcessingSteps(
      SourcePathResolver resolver,
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList<String> ibtoolFlags,
      ProjectFilesystem projectFilesystem,
      boolean isLegacyWatchApp,
      ApplePlatform platform,
      Logger LOG,
      Tool ibtool,
      boolean ibtoolModuleFlag,
      BuildTarget buildTarget,
      Optional<String> binaryName) {
    String sourcePathExtension =
        Files.getFileExtension(sourcePath.toString()).toLowerCase(Locale.US);
    switch (sourcePathExtension) {
      case "plist":
      case "stringsdict":
        LOG.debug("Converting plist %s to binary plist %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new PlistProcessStep(
                projectFilesystem,
                sourcePath,
                Optional.empty(),
                destinationPath,
                ImmutableMap.of(),
                ImmutableMap.of(),
                PlistProcessStep.OutputFormat.BINARY));
        break;
      case "storyboard":
        AppleResourceProcessing.addStoryboardProcessingSteps(
            resolver,
            sourcePath,
            destinationPath,
            stepsBuilder,
            ibtoolFlags,
            isLegacyWatchApp,
            platform,
            projectFilesystem,
            LOG,
            ibtool,
            ibtoolModuleFlag,
            buildTarget,
            binaryName);
        break;
      case "xib":
        String compiledNibFilename =
            Files.getNameWithoutExtension(destinationPath.toString()) + ".nib";
        Path compiledNibPath = destinationPath.getParent().resolve(compiledNibFilename);
        LOG.debug("Compiling XIB %s to NIB %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new IbtoolStep(
                projectFilesystem,
                ibtool.getEnvironment(resolver),
                ibtool.getCommandPrefix(resolver),
                ibtoolModuleFlag ? binaryName : Optional.empty(),
                ImmutableList.<String>builder()
                    .addAll(AppleResourceProcessing.BASE_IBTOOL_FLAGS)
                    .addAll(ibtoolFlags)
                    .addAll(ImmutableList.of("--compile"))
                    .build(),
                sourcePath,
                compiledNibPath));
        break;
      default:
        stepsBuilder.add(CopyStep.forFile(projectFilesystem, sourcePath, destinationPath));
        break;
    }
  }
}
