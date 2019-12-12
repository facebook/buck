/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.CodeSignIdentity;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Contains shared logic for adding resource processing steps to apple build rules */
public class AppleResourceProcessing {
  public static final ImmutableList<String> BASE_IBTOOL_FLAGS =
      ImmutableList.of(
          "--output-format", "human-readable-text", "--notices", "--warnings", "--errors");

  private AppleResourceProcessing() {}

  /** Add Storyboard processing ibtool steps to a build rule */
  public static void addStoryboardProcessingSteps(
      SourcePathResolverAdapter resolver,
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

  /** Adds Variant file processing steps to a build rule */
  public static void addVariantFileProcessingSteps(
      AppleBundleResources resources,
      BuildContext context,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ImmutableList.Builder<Step> stepsBuilder,
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> ibtoolFlags,
      boolean isLegacyWatchApp,
      ApplePlatform platform,
      Logger LOG,
      Tool ibtool,
      boolean ibtoolModuleFlag,
      BuildTarget buildTarget,
      Optional<String> binaryName) {
    for (SourcePath path : resources.getResourceVariantFiles()) {
      Path variantFilePath = context.getSourcePathResolver().getAbsolutePath(path);

      Path variantDirectory = variantFilePath.getParent();
      if (variantDirectory == null || !variantDirectory.toString().endsWith(".lproj")) {
        throw new HumanReadableException(
            "Variant files have to be in a directory with name ending in '.lproj', "
                + "but '%s' is not.",
            variantFilePath);
      }

      Path bundleDestinationPath = dirRoot.resolve(destinations.getResourcesPath());
      Path bundleVariantDestinationPath =
          bundleDestinationPath.resolve(variantDirectory.getFileName());
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  projectFilesystem,
                  bundleVariantDestinationPath)));

      Path destinationPath = bundleVariantDestinationPath.resolve(variantFilePath.getFileName());
      AppleResourceProcessing.addResourceProcessingSteps(
          context.getSourcePathResolver(),
          variantFilePath,
          destinationPath,
          stepsBuilder,
          ibtoolFlags,
          projectFilesystem,
          isLegacyWatchApp,
          platform,
          LOG,
          ibtool,
          ibtoolModuleFlag,
          buildTarget,
          binaryName);
    }
  }

  /** Adds framework processing steps to a build rule */
  public static void addFrameworksProcessingSteps(
      Set<SourcePath> frameworks,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ImmutableList.Builder<Step> stepsBuilder,
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder) {
    if (!frameworks.isEmpty()) {
      Path frameworksDestinationPath = dirRoot.resolve(destinations.getFrameworksPath());
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, frameworksDestinationPath)));
      for (SourcePath framework : frameworks) {
        Path srcPath = context.getSourcePathResolver().getAbsolutePath(framework);
        stepsBuilder.add(
            CopyStep.forDirectory(
                projectFilesystem,
                srcPath,
                frameworksDestinationPath,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
        codeSignOnCopyPathsBuilder.add(frameworksDestinationPath.resolve(srcPath.getFileName()));
      }
    }
  }

  /** Adds the swift stdlib to the bundle if needed */
  public static void addSwiftStdlibStepIfNeeded(
      SourcePathResolverAdapter resolver,
      Path destinationPath,
      Path bundleRoot,
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      ImmutableList.Builder<Step> stepsBuilder,
      boolean isForPackaging,
      String bundleExtension,
      boolean copySwiftStdlibToFrameworks,
      boolean useLipoThin,
      Optional<Tool> swiftStdlibTool,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      Path sdkPath,
      Tool lipo,
      Path bundleBinaryPath,
      AppleBundleDestinations destinations) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    boolean shouldCopySwiftStdlib =
        !bundleExtension.equals(AppleBundleExtension.APPEX.toFileExtension())
            && (!bundleExtension.equals(AppleBundleExtension.FRAMEWORK.toFileExtension())
                || copySwiftStdlibToFrameworks);

    if (swiftStdlibTool.isPresent() && shouldCopySwiftStdlib) {
      String tempDirPattern = isForPackaging ? "__swift_packaging_temp__%s" : "__swift_temp__%s";
      stepsBuilder.add(
          new SwiftStdlibStep(
              projectFilesystem.getRootPath(),
              BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, tempDirPattern),
              sdkPath,
              destinationPath,
              swiftStdlibTool.get().getCommandPrefix(resolver),
              lipo.getCommandPrefix(resolver),
              useLipoThin,
              bundleBinaryPath,
              ImmutableSet.of(
                  bundleRoot.resolve(destinations.getFrameworksPath()),
                  bundleRoot.resolve(destinations.getPlugInsPath())),
              codeSignIdentitySupplier));
    }
  }

  /** Adds Resources processing steps to a build rule */
  public static void addResourceProcessingSteps(
      SourcePathResolverAdapter resolver,
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

  /** Adds required copy resources steps */
  public static void addStepsToCopyResources(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      AppleBundleResources resources,
      boolean verifyResources,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      ImmutableList<String> ibtoolFlags,
      boolean isLegacyWatchApp,
      ApplePlatform platform,
      Logger LOG,
      Tool ibtool,
      boolean ibtoolModuleFlag,
      BuildTarget buildTarget,
      Optional<String> binaryName) {
    boolean hasNoResourceToCopy =
        resources.getResourceDirs().isEmpty()
            && resources.getDirsContainingResourceDirs().isEmpty()
            && resources.getResourceFiles().isEmpty();
    if (hasNoResourceToCopy) {
      return;
    }
    if (verifyResources) {
      verifyResourceConflicts(resources, context.getSourcePathResolver());
    }

    for (AppleBundleDestination bundleDestination : resources.getAllDestinations()) {
      Path bundleDestinationPath = dirRoot.resolve(bundleDestination.getPath(destinations));
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, bundleDestinationPath)));
    }

    for (SourcePathWithAppleBundleDestination dirWithDestination : resources.getResourceDirs()) {
      Path bundleDestinationPath =
          dirRoot.resolve(dirWithDestination.getDestination().getPath(destinations));
      stepsBuilder.add(
          CopyStep.forDirectory(
              projectFilesystem,
              context.getSourcePathResolver().getAbsolutePath(dirWithDestination.getSourcePath()),
              bundleDestinationPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }

    for (SourcePathWithAppleBundleDestination dirWithDestination :
        resources.getDirsContainingResourceDirs()) {
      Path bundleDestinationPath =
          dirRoot.resolve(dirWithDestination.getDestination().getPath(destinations));
      stepsBuilder.add(
          CopyStep.forDirectory(
              projectFilesystem,
              context.getSourcePathResolver().getAbsolutePath(dirWithDestination.getSourcePath()),
              bundleDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    for (SourcePathWithAppleBundleDestination fileWithDestination : resources.getResourceFiles()) {
      Path resolvedFilePath =
          context.getSourcePathResolver().getAbsolutePath(fileWithDestination.getSourcePath());
      Path bundleDestinationPath =
          dirRoot.resolve(fileWithDestination.getDestination().getPath(destinations));
      Path destinationPath = bundleDestinationPath.resolve(resolvedFilePath.getFileName());
      AppleResourceProcessing.addResourceProcessingSteps(
          context.getSourcePathResolver(),
          resolvedFilePath,
          destinationPath,
          stepsBuilder,
          ibtoolFlags,
          projectFilesystem,
          isLegacyWatchApp,
          platform,
          LOG,
          ibtool,
          ibtoolModuleFlag,
          buildTarget,
          binaryName);
      if (fileWithDestination.getCodesignOnCopy()) {
        codeSignOnCopyPathsBuilder.add(destinationPath);
      }
    }
  }

  private static void verifyResourceConflicts(
      AppleBundleResources resources, SourcePathResolverAdapter resolver) {
    // Ensure there are no resources that will overwrite each other
    // TODO: handle ResourceDirsContainingResourceDirs
    for (AppleBundleDestination destination : resources.getAllDestinations()) {
      Set<Path> resourcePaths = new HashSet<>();
      for (SourcePath path :
          Iterables.concat(
              resources.getResourceDirsForDestination(destination),
              resources.getResourceFilesForDestination(destination))) {
        Path pathInBundle = resolver.getRelativePath(path).getFileName();
        if (resourcePaths.contains(pathInBundle)) {
          throw new HumanReadableException(
              "Bundle contains multiple resources with path %s", pathInBundle);
        } else {
          resourcePaths.add(pathInBundle);
        }
      }
    }
  }
}
