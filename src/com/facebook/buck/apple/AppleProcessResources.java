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
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * A BuildRule for processing those resources which should be processed before copying into bundle.
 * Every resource file (provided via `apple_resource` rule) is checked and based on extension it's
 * either skipped by this rule or processed (e.g. plist files converted into binary form) and the
 * result is copied to subdirectory per bundle destination in the output directory of this rule.
 */
public class AppleProcessResources extends ModernBuildRule<AppleProcessResources.Impl> {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-process-resources");

  private static final Logger LOG = Logger.get(AppleProcessResources.class);

  public static final ImmutableList<String> BASE_IBTOOL_FLAGS =
      ImmutableList.of(
          "--output-format", "human-readable-text", "--notices", "--warnings", "--errors");

  public AppleProcessResources(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSet<SourcePathWithAppleBundleDestination> resourceFilesMaybeNeedProcessing,
      ImmutableSet<SourcePath> variantFiles,
      ImmutableList<String> ibtoolFlags,
      boolean isLegacyWatchApp,
      Tool ibtool,
      boolean ibtoolModuleFlag,
      BuildTarget bundleBuildTarget,
      Optional<String> binaryName,
      boolean withDownwardApi,
      ApplePlatform platform,
      AppleBundleDestinations destinations,
      boolean incrementalBundlingEnabled) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            resourceFilesMaybeNeedProcessing,
            variantFiles,
            ibtoolFlags,
            isLegacyWatchApp,
            ibtool,
            ibtoolModuleFlag,
            bundleBuildTarget,
            binaryName,
            withDownwardApi,
            platform,
            destinations,
            incrementalBundlingEnabled));
  }

  /** Checks if resource should be processed before copying it into bundle */
  public static boolean shouldBeProcessed(
      SourcePathWithAppleBundleDestination pathWithDestination,
      SourcePathResolverAdapter sourcePathResolver) {
    RelPath rawFilePath =
        sourcePathResolver.getCellUnsafeRelPath(pathWithDestination.getSourcePath());
    String rawFileExtension = Files.getFileExtension(rawFilePath.toString()).toLowerCase(Locale.US);
    switch (rawFileExtension) {
      case "plist":
      case "stringsdict":
      case "storyboard":
      case "xib":
        return true;
      default:
        return false;
    }
  }

  public static RelPath directoryNameWithProcessedFilesForDestination(
      AppleBundleDestination destination) {
    return RelPath.get(destination.name());
  }

  @Nonnull
  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  public Optional<SourcePath> getSourcePathToContentHashes() {
    return getBuildable().contentHashesOutput.map(this::getSourcePath);
  }

  /** Internal buildable implementation */
  static class Impl implements Buildable {

    @AddToRuleKey
    private final ImmutableSet<SourcePathWithAppleBundleDestination>
        resourceFilesMaybeNeedProcessing;

    @AddToRuleKey private final ImmutableSet<SourcePath> variantFiles;
    @AddToRuleKey private final OutputPath output;
    @AddToRuleKey private final Optional<OutputPath> contentHashesOutput;
    @AddToRuleKey private final ImmutableList<String> ibtoolFlags;
    @AddToRuleKey private final boolean isLegacyWatchApp;
    @AddToRuleKey private final Tool ibtool;
    @AddToRuleKey private final boolean ibtoolModuleFlag;
    @AddToRuleKey private final BuildTarget bundleBuildTarget;
    @AddToRuleKey private final Optional<String> binaryName;
    @AddToRuleKey private final boolean withDownwardApi;
    @AddToRuleKey private final ApplePlatform platform;
    @AddToRuleKey private final AppleBundleDestinations destinations;

    public Impl(
        ImmutableSet<SourcePathWithAppleBundleDestination> resourceFilesMaybeNeedProcessing,
        ImmutableSet<SourcePath> variantFiles,
        ImmutableList<String> ibtoolFlags,
        boolean isLegacyWatchApp,
        Tool ibtool,
        boolean ibtoolModuleFlag,
        BuildTarget bundleBuildTarget,
        Optional<String> binaryName,
        boolean withDownwardApi,
        ApplePlatform platform,
        AppleBundleDestinations destinations,
        boolean incrementalBundlingEnabled) {
      this.resourceFilesMaybeNeedProcessing = resourceFilesMaybeNeedProcessing;
      this.variantFiles = variantFiles;
      this.ibtoolFlags = ibtoolFlags;
      this.isLegacyWatchApp = isLegacyWatchApp;
      this.ibtool = ibtool;
      this.ibtoolModuleFlag = ibtoolModuleFlag;
      this.bundleBuildTarget = bundleBuildTarget;
      this.binaryName = binaryName;
      this.withDownwardApi = withDownwardApi;
      this.platform = platform;
      this.destinations = destinations;
      output = new OutputPath("BundleParts");
      contentHashesOutput =
          incrementalBundlingEnabled
              ? Optional.of(new OutputPath("content_hashes.json"))
              : Optional.empty();
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      RelPath cellPath =
          ProjectFilesystemUtils.relativize(
              filesystem.getRootPath(), buildContext.getBuildCellRootPath());
      Set<AppleBundleDestination> usedDestinations = new HashSet<>();
      ImmutableList.Builder<Step> processStepsBuilder = new ImmutableList.Builder<>();
      resourceFilesMaybeNeedProcessing.forEach(
          pathWithDestination -> {
            boolean shouldBeProcessed =
                shouldBeProcessed(pathWithDestination, buildContext.getSourcePathResolver());
            boolean isProcessed =
                addStepsToProcessResourceIfNeeded(
                    processStepsBuilder,
                    filesystem,
                    buildContext.getSourcePathResolver(),
                    outputPathResolver,
                    pathWithDestination,
                    Optional.empty(),
                    cellPath);
            if (shouldBeProcessed != isProcessed) {
              throw new IllegalStateException(
                  "Apple bundle resource processing logic is inconsistent");
            }
            if (isProcessed) {
              usedDestinations.add(pathWithDestination.getDestination());
            }
          });

      for (SourcePath path : variantFiles) {
        RelPath rawVariantFilePath =
            buildContext.getSourcePathResolver().getCellUnsafeRelPath(path);
        RelPath rawVariantDirectory = rawVariantFilePath.getParent();
        if (rawVariantDirectory == null || !rawVariantDirectory.toString().endsWith(".lproj")) {
          throw new HumanReadableException(
              "Variant files have to be in a directory with name ending in '.lproj', "
                  + "but '%s' is not.",
              rawVariantFilePath);
        }

        usedDestinations.add(AppleBundleDestination.RESOURCES);

        RelPath outputDirPath =
            outputPathResolver
                .resolvePath(output)
                .resolve(
                    directoryNameWithProcessedFilesForDestination(AppleBundleDestination.RESOURCES))
                .resolve(RelPath.of(rawVariantDirectory.getFileName()));

        processStepsBuilder.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), filesystem, outputDirPath)));

        if (!addStepsToProcessResourceIfNeeded(
            processStepsBuilder,
            filesystem,
            buildContext.getSourcePathResolver(),
            outputPathResolver,
            SourcePathWithAppleBundleDestination.of(path, AppleBundleDestination.RESOURCES),
            Optional.of(outputDirPath),
            cellPath)) {
          // Not processable, just copy
          RelPath destinationPath =
              outputDirPath.resolveRel(rawVariantFilePath.getFileName().toString());
          processStepsBuilder.add(
              CopyStep.forFile(filesystem, rawVariantFilePath, destinationPath));
        }
      }

      ImmutableList<Step> processSteps = processStepsBuilder.build();

      ImmutableList.Builder<Step> stepsBuilder = new ImmutableList.Builder<>();
      usedDestinations.forEach(
          destination -> {
            RelPath outputDirPath =
                outputPathResolver
                    .resolvePath(output)
                    .resolve(directoryNameWithProcessedFilesForDestination(destination));
            stepsBuilder.add(
                MkdirStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        buildContext.getBuildCellRootPath(), filesystem, outputDirPath)));
          });
      stepsBuilder.addAll(processSteps);

      addStepsToComputeAndPersistHashesIfNeeded(
          stepsBuilder, outputPathResolver, filesystem, usedDestinations, buildContext);

      return stepsBuilder.build();
    }

    private boolean addStepsToProcessResourceIfNeeded(
        ImmutableList.Builder<Step> stepsBuilder,
        ProjectFilesystem filesystem,
        SourcePathResolverAdapter sourcePathResolver,
        OutputPathResolver outputPathResolver,
        SourcePathWithAppleBundleDestination pathWithDestination,
        Optional<RelPath> customTargetDirPath,
        RelPath cellPath) {
      RelPath rawFilePath =
          sourcePathResolver.getCellUnsafeRelPath(pathWithDestination.getSourcePath());

      RelPath targetDirectory =
          customTargetDirPath.orElse(
              outputPathResolver
                  .resolvePath(output)
                  .resolve(
                      directoryNameWithProcessedFilesForDestination(
                          pathWithDestination.getDestination())));

      Path processedFileName =
          sourcePathResolver
              .getCellUnsafeRelPath(pathWithDestination.getSourcePath())
              .getFileName();
      RelPath processedFilePath = targetDirectory.resolve(RelPath.of(processedFileName));

      String rawFileExtension =
          Files.getFileExtension(rawFilePath.toString()).toLowerCase(Locale.US);

      switch (rawFileExtension) {
        case "plist":
        case "stringsdict":
          addStepsToProcessPlist(stepsBuilder, filesystem, rawFilePath, processedFilePath);
          return true;
        case "storyboard":
          addStoryboardProcessingSteps(
              sourcePathResolver,
              rawFilePath.getPath(),
              processedFilePath.getPath(),
              stepsBuilder,
              cellPath,
              filesystem);
          return true;
        case "xib":
          addStepsToProcessXib(
              stepsBuilder,
              filesystem,
              rawFilePath,
              processedFilePath,
              sourcePathResolver,
              cellPath);
          return true;
        default:
          return false;
      }
    }

    private void addStepsToProcessPlist(
        ImmutableList.Builder<Step> stepsBuilder,
        ProjectFilesystem filesystem,
        RelPath rawPath,
        RelPath processedPath) {
      LOG.debug("Converting plist %s to binary plist %s", rawPath, processedPath);
      stepsBuilder.add(
          new PlistProcessStep(
              filesystem,
              rawPath.getPath(),
              Optional.empty(),
              processedPath.getPath(),
              ImmutableMap.of(),
              ImmutableMap.of(),
              PlistProcessStep.OutputFormat.BINARY));
    }

    private void addStoryboardProcessingSteps(
        SourcePathResolverAdapter resolver,
        Path sourcePath,
        Path destinationPath,
        ImmutableList.Builder<Step> stepsBuilder,
        RelPath cellPath,
        ProjectFilesystem projectFilesystem) {
      ImmutableList<String> modifiedFlags =
          ImmutableList.<String>builder().addAll(BASE_IBTOOL_FLAGS).addAll(ibtoolFlags).build();

      if (platform.getName().contains("watch") || isLegacyWatchApp) {
        LOG.debug(
            "Compiling storyboard %s to storyboardc %s and linking", sourcePath, destinationPath);

        RelPath compiledStoryboardPath =
            BuildTargetPaths.getScratchPath(projectFilesystem, bundleBuildTarget, "%s.storyboardc");

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
                cellPath,
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
                cellPath,
                withDownwardApi));

      } else {
        LOG.debug("Compiling storyboard %s to storyboardc %s", sourcePath, destinationPath);

        String compiledStoryboardFilename =
            Files.getNameWithoutExtension(destinationPath.toString()) + ".storyboardc";

        Path compiledStoryboardPath =
            destinationPath.getParent().resolve(compiledStoryboardFilename);

        stepsBuilder.add(
            new IbtoolStep(
                projectFilesystem,
                ibtool.getEnvironment(resolver),
                ibtool.getCommandPrefix(resolver),
                ibtoolModuleFlag ? binaryName : Optional.empty(),
                ImmutableList.<String>builder().addAll(modifiedFlags).add("--compile").build(),
                sourcePath,
                compiledStoryboardPath,
                cellPath,
                withDownwardApi));
      }
    }

    private void addStepsToProcessXib(
        ImmutableList.Builder<Step> stepsBuilder,
        ProjectFilesystem filesystem,
        RelPath rawPath,
        RelPath processedPath,
        SourcePathResolverAdapter sourcePathResolver,
        RelPath cellPath) {
      String compiledNibFilename = Files.getNameWithoutExtension(processedPath.toString()) + ".nib";
      Path compiledNibPath = processedPath.getParent().resolve(compiledNibFilename);
      LOG.debug("Compiling XIB %s to NIB %s", rawPath, processedPath);
      stepsBuilder.add(
          new IbtoolStep(
              filesystem,
              ibtool.getEnvironment(sourcePathResolver),
              ibtool.getCommandPrefix(sourcePathResolver),
              ibtoolModuleFlag ? binaryName : Optional.empty(),
              ImmutableList.<String>builder()
                  .addAll(BASE_IBTOOL_FLAGS)
                  .addAll(ibtoolFlags)
                  .addAll(ImmutableList.of("--compile"))
                  .build(),
              rawPath.getPath(),
              compiledNibPath,
              cellPath,
              withDownwardApi));
    }

    private void addStepsToComputeAndPersistHashesIfNeeded(
        ImmutableList.Builder<Step> stepsBuilder,
        OutputPathResolver outputPathResolver,
        ProjectFilesystem filesystem,
        Set<AppleBundleDestination> usedDestinations,
        BuildContext buildContext) {
      if (!contentHashesOutput.isPresent()) {
        return;
      }

      ImmutableSortedMap.Builder<RelPath, String> bundleRootRelativePathToHashBuilder =
          ImmutableSortedMap.orderedBy(RelPath.comparator());

      usedDestinations.forEach(
          destination -> {
            AbsPath outputDirPath =
                AbsPath.of(buildContext.getBuildCellRootPath())
                    .resolve(outputPathResolver.resolvePath(output))
                    .resolve(directoryNameWithProcessedFilesForDestination(destination));
            ImmutableMap.Builder<RelPath, String> containerDirRelativePathToHashBuilder =
                ImmutableMap.builder();
            stepsBuilder.add(
                new AppleComputeDirectoryFirstLevelContentHashesStep(
                    outputDirPath, filesystem, containerDirRelativePathToHashBuilder),
                new AbstractExecutionStep("apple-bundle-memoize-hashes") {
                  @Override
                  public StepExecutionResult execute(StepExecutionContext context) {
                    for (ImmutableMap.Entry<RelPath, String> entry :
                        containerDirRelativePathToHashBuilder.build().entrySet()) {
                      bundleRootRelativePathToHashBuilder.put(
                          RelPath.of(
                              destination.getPath(destinations).resolve(entry.getKey().getPath())),
                          entry.getValue());
                    }
                    return StepExecutionResults.SUCCESS;
                  }
                });
          });

      Path hashesOutputPath = outputPathResolver.resolvePath(contentHashesOutput.get()).getPath();
      stepsBuilder.add(
          new AppleWriteHashPerFileStep(
              "persist-processed-resources-hashes",
              bundleRootRelativePathToHashBuilder::build,
              hashesOutputPath,
              filesystem));
    }
  }
}
