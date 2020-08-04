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
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
      Optional<String> binaryName,
      boolean withDownwardApi) {
    ImmutableList<String> modifiedFlags =
        ImmutableList.<String>builder().addAll(BASE_IBTOOL_FLAGS).addAll(ibtoolFlags).build();

    if (platform.getName().contains("watch") || isLegacyWatchApp) {
      LOG.debug(
          "Compiling storyboard %s to storyboardc %s and linking", sourcePath, destinationPath);

      RelPath compiledStoryboardPath =
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
              compiledStoryboardPath.getPath(),
              withDownwardApi));

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
              compiledStoryboardPath.getPath(),
              destinationPath.getParent(),
              withDownwardApi));

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
              compiledStoryboardPath,
              withDownwardApi));
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
      Optional<String> binaryName,
      boolean withDownwardApi) {
    for (SourcePath path : resources.getResourceVariantFiles()) {
      AbsPath variantFilePath = context.getSourcePathResolver().getAbsolutePath(path);

      AbsPath variantDirectory = variantFilePath.getParent();
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
          variantFilePath.getPath(),
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
          binaryName,
          withDownwardApi);
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
      Optional<Tool> swiftStdlibTool,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      Path sdkPath,
      Tool lipo,
      Path bundleBinaryPath,
      AppleBundleDestinations destinations,
      boolean sliceAppPackageSwiftRuntime,
      boolean sliceAppBundleSwiftRuntime,
      boolean withDownwardApi) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    boolean shouldCopySwiftStdlib =
        !bundleExtension.equals(AppleBundleExtension.APPEX.fileExtension)
            && (!bundleExtension.equals(AppleBundleExtension.FRAMEWORK.fileExtension)
                || copySwiftStdlibToFrameworks);

    if (swiftStdlibTool.isPresent() && shouldCopySwiftStdlib) {
      String tempDirPattern = isForPackaging ? "__swift_packaging_temp__%s" : "__swift_temp__%s";
      RelPath tempPath =
          BuildTargetPaths.getScratchPath(projectFilesystem, buildTarget, tempDirPattern);

      stepsBuilder.addAll(MakeCleanDirectoryStep.of(BuildCellRelativePath.of(tempPath)));

      boolean sliceArchitectures =
          (isForPackaging ? sliceAppPackageSwiftRuntime : sliceAppBundleSwiftRuntime);
      stepsBuilder.add(
          new SwiftStdlibStep(
              projectFilesystem.getRootPath(),
              tempPath.getPath(),
              sdkPath,
              destinationPath,
              swiftStdlibTool.get().getCommandPrefix(resolver),
              lipo.getCommandPrefix(resolver),
              bundleBinaryPath,
              ImmutableSet.of(
                  bundleRoot.resolve(destinations.getFrameworksPath()),
                  bundleRoot.resolve(destinations.getPlugInsPath())),
              codeSignIdentitySupplier,
              sliceArchitectures,
              withDownwardApi));
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
      Optional<String> binaryName,
      boolean withDownwardApi) {
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
            binaryName,
            withDownwardApi);
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
                compiledNibPath,
                withDownwardApi));
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
      ImmutableList<AppleBundlePart> bundleParts,
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
      Optional<String> binaryName,
      boolean withDownwardApi) {
    addStepsToCreateDirectoriesWhereBundlePartsAreCopied(
        context, stepsBuilder, resources, bundleParts, dirRoot, destinations, projectFilesystem);
    addStepsToCopyDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        codeSignOnCopyPathsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem);
    addStepsToCopyContentOfDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem);
    addStepsToProcessAndCopyFiles(
        context.getSourcePathResolver(),
        stepsBuilder,
        codeSignOnCopyPathsBuilder,
        resources,
        dirRoot,
        destinations,
        projectFilesystem,
        ibtoolFlags,
        isLegacyWatchApp,
        platform,
        LOG,
        ibtool,
        ibtoolModuleFlag,
        buildTarget,
        binaryName,
        withDownwardApi);
    addStepsToCopyFilesNotNeedingProcessing(
        context.getSourcePathResolver(),
        stepsBuilder,
        codeSignOnCopyPathsBuilder,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem);
  }

  private static void addStepsToCreateDirectoriesWhereBundlePartsAreCopied(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem) {
    Set<AppleBundleDestination> usedDestinations =
        Stream.concat(
                resources.getAllDestinations().stream(),
                bundleParts.stream().map(AppleBundlePart::getDestination))
            .collect(Collectors.toSet());
    for (AppleBundleDestination bundleDestination : usedDestinations) {
      Path bundleDestinationPath = dirRoot.resolve(bundleDestination.getPath(destinations));
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, bundleDestinationPath)));
    }
  }

  private static void addStepsToCopyDirectories(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem) {

    List<DirectoryAppleBundlePart> directoryBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryAppleBundlePart)
            .map(p -> (DirectoryAppleBundlePart) p)
            .collect(Collectors.toList());

    List<SourcePathWithAppleBundleDestination> directoriesToCopy =
        Stream.concat(
                resources.getResourceDirs().stream(),
                directoryBundleParts.stream()
                    .map(
                        p ->
                            SourcePathWithAppleBundleDestination.of(
                                p.getSourcePath(), p.getDestination(), p.getCodesignOnCopy())))
            .collect(Collectors.toList());
    for (SourcePathWithAppleBundleDestination dirWithDestination : directoriesToCopy) {
      Path resolvedDirPath =
          sourcePathResolver.getAbsolutePath(dirWithDestination.getSourcePath()).getPath();
      Path bundleDestinationPath =
          dirRoot.resolve(dirWithDestination.getDestination().getPath(destinations));
      stepsBuilder.add(
          CopyStep.forDirectory(
              projectFilesystem,
              resolvedDirPath,
              bundleDestinationPath,
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      if (dirWithDestination.getCodesignOnCopy()) {
        codeSignOnCopyPathsBuilder.add(
            bundleDestinationPath.resolve(resolvedDirPath.getFileName()));
      }
    }
  }

  private static void addStepsToCopyContentOfDirectories(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem) {

    List<DirectoryContentAppleBundlePart> directoriesWithContentBundleParts =
        bundleParts.stream()
            .filter(p -> p instanceof DirectoryContentAppleBundlePart)
            .map(p -> (DirectoryContentAppleBundlePart) p)
            .collect(Collectors.toList());

    List<SourcePathWithAppleBundleDestination> directoriesWithContent =
        Stream.concat(
                resources.getDirsContainingResourceDirs().stream(),
                directoriesWithContentBundleParts.stream()
                    .map(
                        p ->
                            SourcePathWithAppleBundleDestination.of(
                                p.getSourcePath(), p.getDestination())))
            .collect(Collectors.toList());
    for (SourcePathWithAppleBundleDestination dirWithDestination : directoriesWithContent) {
      Path bundleDestinationPath =
          dirRoot.resolve(dirWithDestination.getDestination().getPath(destinations));
      stepsBuilder.add(
          CopyStep.forDirectory(
              projectFilesystem,
              sourcePathResolver.getAbsolutePath(dirWithDestination.getSourcePath()).getPath(),
              bundleDestinationPath,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }
  }

  public static void addStepsToProcessAndCopyFiles(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      AppleBundleResources resources,
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
      Optional<String> binaryName,
      boolean withDownwardApi) {
    for (SourcePathWithAppleBundleDestination fileWithDestination : resources.getResourceFiles()) {
      AbsPath resolvedFilePath =
          sourcePathResolver.getAbsolutePath(fileWithDestination.getSourcePath());
      Path bundleDestinationPath =
          dirRoot.resolve(fileWithDestination.getDestination().getPath(destinations));
      Path destinationPath = bundleDestinationPath.resolve(resolvedFilePath.getFileName());
      AppleResourceProcessing.addResourceProcessingSteps(
          sourcePathResolver,
          resolvedFilePath.getPath(),
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
          binaryName,
          withDownwardApi);
      if (fileWithDestination.getCodesignOnCopy()) {
        codeSignOnCopyPathsBuilder.add(destinationPath);
      }
    }
  }

  private static void addStepsToCopyFilesNotNeedingProcessing(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem) {

    List<FileAppleBundlePart> filesToCopyWithoutProcessing =
        bundleParts.stream()
            .filter(p -> p instanceof FileAppleBundlePart)
            .map(p -> (FileAppleBundlePart) p)
            .collect(Collectors.toList());

    Set<AbsPath> ignoreIfMissingPaths = new HashSet<>();

    List<CopySpec> copySpecs = new LinkedList<>();

    for (FileAppleBundlePart bundlePart : filesToCopyWithoutProcessing) {
      CopySpec copySpec = new CopySpec(bundlePart, sourcePathResolver, destinations);
      copySpecs.add(copySpec);

      if (bundlePart.getIgnoreIfMissing()) {
        ignoreIfMissingPaths.add(copySpec.sourcePath);
      }

      if (bundlePart.getCodesignOnCopy()) {
        Path toPath = dirRoot.resolve(copySpec.getDestinationPathRelativeToBundleRoot().getPath());
        codeSignOnCopyPathsBuilder.add(toPath);
      }
    }

    stepsBuilder.add(
        new AbstractExecutionStep("copy-files-from-bundle-parts") {
          @Override
          public StepExecutionResult execute(StepExecutionContext stepContext) throws IOException {
            for (CopySpec copySpec : copySpecs) {
              AbsPath fromPath = copySpec.getSourcePath();
              if (ignoreIfMissingPaths.contains(fromPath)
                  && !projectFilesystem.exists(fromPath.getPath())) {
                continue;
              }
              projectFilesystem.copy(
                  fromPath.getPath(),
                  dirRoot.resolve(copySpec.getDestinationPathRelativeToBundleRoot().getPath()),
                  CopySourceMode.FILE);
            }
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  /** Checks and throws an exception if parts of bundle have conflicting paths */
  public static void verifyResourceConflicts(
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      SourcePathResolverAdapter resolver,
      AppleBundleDestinations destinations) {
    // Ensure there are no resources that will overwrite each other
    // TODO: handle ResourceDirsContainingResourceDirs

    List<CopySpec> copySpecs =
        Stream.concat(
                Stream.concat(
                        resources.getResourceDirs().stream(), resources.getResourceFiles().stream())
                    .map(e -> new CopySpec(e, resolver, destinations)),
                Stream.concat(
                    bundleParts.stream()
                        .filter(p -> p instanceof DirectoryAppleBundlePart)
                        .map(p -> (DirectoryAppleBundlePart) p)
                        .map(e -> new CopySpec(e, resolver, destinations)),
                    bundleParts.stream()
                        .filter(p -> p instanceof FileAppleBundlePart)
                        .map(p -> (FileAppleBundlePart) p)
                        .map(e -> new CopySpec(e, resolver, destinations))))
            .collect(Collectors.toList());

    Map<RelPath, AbsPath> encounteredDestinationToSourcePaths = new HashMap<>();
    for (CopySpec copySpec : copySpecs) {
      AbsPath sourcePath = copySpec.getSourcePath();
      RelPath destinationPath = copySpec.getDestinationPathRelativeToBundleRoot();
      if (encounteredDestinationToSourcePaths.containsKey(destinationPath)) {
        AbsPath encounteredSourcePath = encounteredDestinationToSourcePaths.get(destinationPath);
        throw new HumanReadableException(
            "Bundle contains multiple resources with path '%s'. Source files are '%s' and '%s'",
            destinationPath, sourcePath, encounteredSourcePath);
      } else {
        encounteredDestinationToSourcePaths.put(destinationPath, sourcePath);
      }
    }
  }

  private static class CopySpec {
    private final AbsPath sourcePath;
    private final RelPath destinationPathRelativeToBundleRoot;

    public CopySpec(
        SourcePathWithAppleBundleDestination pathWithDestination,
        SourcePathResolverAdapter sourcePathResolver,
        AppleBundleDestinations destinations) {
      this.sourcePath = sourcePathResolver.getAbsolutePath(pathWithDestination.getSourcePath());
      this.destinationPathRelativeToBundleRoot =
          destinationPathRelativeToBundleRoot(
              sourcePathResolver,
              destinations,
              pathWithDestination.getSourcePath(),
              pathWithDestination.getDestination(),
              Optional.empty());
    }

    public CopySpec(
        FileAppleBundlePart bundlePart,
        SourcePathResolverAdapter sourcePathResolver,
        AppleBundleDestinations destinations) {
      this.sourcePath = sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath());
      this.destinationPathRelativeToBundleRoot =
          destinationPathRelativeToBundleRoot(
              sourcePathResolver,
              destinations,
              bundlePart.getSourcePath(),
              bundlePart.getDestination(),
              bundlePart.getNewName());
    }

    public CopySpec(
        DirectoryAppleBundlePart bundlePart,
        SourcePathResolverAdapter sourcePathResolver,
        AppleBundleDestinations destinations) {
      this.sourcePath = sourcePathResolver.getAbsolutePath(bundlePart.getSourcePath());
      this.destinationPathRelativeToBundleRoot =
          destinationPathRelativeToBundleRoot(
              sourcePathResolver,
              destinations,
              bundlePart.getSourcePath(),
              bundlePart.getDestination(),
              Optional.empty());
    }

    public AbsPath getSourcePath() {
      return sourcePath;
    }

    public RelPath getDestinationPathRelativeToBundleRoot() {
      return destinationPathRelativeToBundleRoot;
    }

    private static RelPath destinationPathRelativeToBundleRoot(
        SourcePathResolverAdapter sourcePathResolver,
        AppleBundleDestinations destinations,
        SourcePath sourcePath,
        AppleBundleDestination destination,
        Optional<String> maybeNewName) {
      RelPath destinationDirectoryPath = RelPath.of(destination.getPath(destinations));
      AbsPath resolvedSourcePath = sourcePathResolver.getAbsolutePath(sourcePath);
      String destinationFileName = maybeNewName.orElse(resolvedSourcePath.getFileName().toString());
      return destinationDirectoryPath.resolveRel(destinationFileName);
    }
  }
}
