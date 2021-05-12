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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Contains shared logic for adding resource processing steps to apple build rules */
public class AppleResourceProcessing {
  private AppleResourceProcessing() {}

  /** Adds required copy resources steps */
  public static void addStepsToCopyResources(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      SourcePath processedResourcesDir,
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier,
      Supplier<Optional<Map<RelPath, String>>> oldContentHashesSupplier,
      Optional<Supplier<Map<RelPath, String>>> newContentHashesSupplier,
      Supplier<List<AppleBundleComponentCopySpec>> embeddedBundlesCopySpecsSupplier) {
    addStepsToCreateDirectoriesWhereBundlePartsAreCopied(
        context,
        stepsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem,
        embeddedBundlesCopySpecsSupplier);
    addStepsToCopyContentOfDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem,
        shouldPerformIncrementalBuildSupplier,
        oldContentHashesSupplier,
        newContentHashesSupplier);
    addStepsToCopyProcessedResources(
        context.getSourcePathResolver(),
        stepsBuilder,
        resources,
        dirRoot,
        destinations,
        projectFilesystem,
        processedResourcesDir,
        shouldPerformIncrementalBuildSupplier,
        oldContentHashesSupplier,
        newContentHashesSupplier);
    addStepsToCopyNonProcessedFilesAndDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem,
        shouldPerformIncrementalBuildSupplier,
        oldContentHashesSupplier,
        newContentHashesSupplier,
        embeddedBundlesCopySpecsSupplier);
  }

  private static void addStepsToCreateDirectoriesWhereBundlePartsAreCopied(
      BuildContext context,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      Supplier<List<AppleBundleComponentCopySpec>> embeddedBundlesCopySpecsSupplier) {
    Set<AppleBundleDestination> usedDestinations =
        Stream.concat(
                resources.getAllDestinations().stream(),
                bundleParts.stream().map(AppleBundlePart::getDestination))
            .collect(Collectors.toSet());
    // Every file or directory except for those managed by EmbeddedBundleAppleBundlePart will be
    // copied directly into corresponding "apple bundle destination", so it's enough to just create
    // those.
    for (AppleBundleDestination bundleDestination : usedDestinations) {
      Path bundleDestinationPath = dirRoot.resolve(bundleDestination.getPath(destinations));
      stepsBuilder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, bundleDestinationPath)));
    }
    // We handle embedded bundles separately, because files and directories they manage are not
    // necessarily copied directly into "apple bundle destination" directories. In that case we have
    // to prepare copy destination directories.
    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-prepare-embedded-bundles-destination-directories") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            Set<Path> directoriesToCreate =
                embeddedBundlesCopySpecsSupplier.get().stream()
                    .map(
                        spec ->
                            dirRoot
                                .resolve(spec.getDestinationPathRelativeToBundleRoot().getPath())
                                .getParent())
                    .collect(Collectors.toSet());
            for (Path dir : directoriesToCreate) {
              projectFilesystem.createParentDirs(dir.resolve("dummy"));
            }
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private static void addStepsToCopyContentOfDirectories(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier,
      Supplier<Optional<Map<RelPath, String>>> oldContentHashesSupplier,
      Optional<Supplier<Map<RelPath, String>>> newContentHashesSupplier) {

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

    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-copy-directories-content") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            for (SourcePathWithAppleBundleDestination dirWithDestination : directoriesWithContent) {
              RelPath destinationDirectoryPath =
                  RelPath.of(dirWithDestination.getDestination().getPath(destinations));
              AbsPath resolvedSourcePath =
                  sourcePathResolver.getAbsolutePath(dirWithDestination.getSourcePath());
              if (shouldPerformIncrementalBuildSupplier.get()) {
                copyContentsOfDirectoryIfNeededToBundleWithIncrementalSupport(
                    resolvedSourcePath,
                    destinationDirectoryPath,
                    dirRoot,
                    projectFilesystem,
                    oldContentHashesSupplier
                        .get()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for previous build should be computed when incremental build is performed")),
                    newContentHashesSupplier
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for current build should be computed when incremental build is performed"))
                        .get());
              } else {
                projectFilesystem.copy(
                    resolvedSourcePath.getPath(),
                    dirRoot.resolve(destinationDirectoryPath.getPath()),
                    CopySourceMode.DIRECTORY_CONTENTS_ONLY);
              }
            }
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private static void copyContentsOfDirectoryIfNeededToBundleWithIncrementalSupport(
      AbsPath sourceContainerDirectoryPath,
      RelPath destinationDirectoryPath,
      Path dirRoot,
      ProjectFilesystem projectFilesystem,
      Map<RelPath, String> oldContentHashesSupplier,
      Map<RelPath, String> newContentHashesSupplier)
      throws IOException {

    ImmutableSet.Builder<Path> pathsToDeleteBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<AppleBundleComponentCopySpec> copySpecsBuilder = ImmutableSet.builder();

    for (String fileName :
        Objects.requireNonNull(new File(sourceContainerDirectoryPath.toUri()).list())) {
      AbsPath fromPath = sourceContainerDirectoryPath.resolve(fileName);
      RelPath toPathRelativeToBundleRoot = destinationDirectoryPath.resolveRel(fileName);
      prepareCopyFileToBundleWithIncrementalSupport(
          new AppleBundleComponentCopySpec(fromPath, toPathRelativeToBundleRoot, false),
          newContentHashesSupplier,
          oldContentHashesSupplier,
          projectFilesystem,
          dirRoot,
          pathsToDeleteBuilder,
          copySpecsBuilder);
    }
    for (Path pathToDelete : pathsToDeleteBuilder.build()) {
      projectFilesystem.deleteRecursivelyIfExists(pathToDelete);
    }
    for (AppleBundleComponentCopySpec spec : copySpecsBuilder.build()) {
      spec.performCopy(projectFilesystem, dirRoot);
    }
  }

  private static void prepareCopyFileToBundleWithIncrementalSupport(
      AppleBundleComponentCopySpec copySpec,
      Map<RelPath, String> newContentHashesSupplier,
      Map<RelPath, String> oldContentHashesSupplier,
      ProjectFilesystem projectFilesystem,
      Path dirRoot,
      ImmutableSet.Builder<Path> pathsToDeleteBuilder,
      ImmutableSet.Builder<AppleBundleComponentCopySpec> copySpecsBuilder) {
    RelPath toPathRelativeToBundleRoot = copySpec.getDestinationPathRelativeToBundleRoot();
    String oldHash = oldContentHashesSupplier.get(toPathRelativeToBundleRoot);
    String newHash = newContentHashesSupplier.get(toPathRelativeToBundleRoot);
    Path toPathRelativeToProjectRoot = dirRoot.resolve(toPathRelativeToBundleRoot.getPath());
    Preconditions.checkState(
        newHash != null
            || (copySpec.ignoreIfMissing()
                && !projectFilesystem.exists(toPathRelativeToProjectRoot)),
        "Expecting a hash to be computed when incremental bundling is enabled unless file is missing and such case should be ignored");
    if (oldHash != null && oldHash.equals(newHash)) {
      return;
    }
    pathsToDeleteBuilder.add(dirRoot.resolve(toPathRelativeToBundleRoot.getPath()));
    copySpecsBuilder.add(copySpec);
  }

  private static void addStepsToCopyProcessedResources(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      SourcePath processedResourcesDir,
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier,
      Supplier<Optional<Map<RelPath, String>>> oldContentHashesSupplier,
      Optional<Supplier<Map<RelPath, String>>> newContentHashesSupplier) {
    Set<AppleBundleDestination> destinationsForAllProcessedResources = new HashSet<>();
    {
      if (!resources.getResourceVariantFiles().isEmpty()
          || !resources.getNamedResourceVariantFiles().isEmpty()) {
        destinationsForAllProcessedResources.add(AppleBundleDestination.RESOURCES);
      }
      destinationsForAllProcessedResources.addAll(
          resources.getResourceFiles().stream()
              .filter(
                  pathWithDestination ->
                      AppleProcessResources.shouldBeProcessed(
                          pathWithDestination, sourcePathResolver))
              .map(SourcePathWithAppleBundleDestination::getDestination)
              .collect(Collectors.toSet()));
    }
    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-copy-processed-resources") {
          @Override
          public StepExecutionResult execute(StepExecutionContext stepContext) throws IOException {
            AbsPath rootDirWithProcessedResourcesPath =
                sourcePathResolver.getAbsolutePath(processedResourcesDir);

            for (AppleBundleDestination destination : destinationsForAllProcessedResources) {
              RelPath subdirectoryNameForDestination =
                  AppleProcessResources.directoryNameWithProcessedFilesForDestination(destination);
              AbsPath processedResourcesContainerDirForDestination =
                  rootDirWithProcessedResourcesPath.resolve(subdirectoryNameForDestination);
              if (shouldPerformIncrementalBuildSupplier.get()) {
                copyContentsOfDirectoryIfNeededToBundleWithIncrementalSupport(
                    processedResourcesContainerDirForDestination,
                    RelPath.of(destination.getPath(destinations)),
                    dirRoot,
                    projectFilesystem,
                    oldContentHashesSupplier
                        .get()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for previous build should be computed when incremental build is performed")),
                    newContentHashesSupplier
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for current build should be computed when incremental build is performed"))
                        .get());
              } else {
                projectFilesystem.copy(
                    processedResourcesContainerDirForDestination.getPath(),
                    dirRoot.resolve(destination.getPath(destinations)),
                    CopySourceMode.DIRECTORY_CONTENTS_ONLY);
              }
            }

            return StepExecutionResults.SUCCESS;
          }
        });
  }

  /**
   * All files and directories which are good to be copied to the result apple bundle without any
   * processing are treated as "non processed". That includes any file or directory provided as
   * `AppleBundlePart`, all directories from resources (provided via `apple_resource` rule), all
   * files from resources that are skipped by `AppleProcessResources` build rule (based on
   * extension).
   */
  private static void addStepsToCopyNonProcessedFilesAndDirectories(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      Supplier<Boolean> shouldPerformIncrementalBuildSupplier,
      Supplier<Optional<Map<RelPath, String>>> oldContentHashesSupplier,
      Optional<Supplier<Map<RelPath, String>>> newContentHashesSupplier,
      Supplier<List<AppleBundleComponentCopySpec>> embeddedBundlesRelatedCopySpecsSupplier) {

    List<AppleBundleComponentCopySpec> copySpecs = new LinkedList<>();

    {
      List<FileAppleBundlePart> filesBundleParts =
          bundleParts.stream()
              .filter(p -> p instanceof FileAppleBundlePart)
              .map(p -> (FileAppleBundlePart) p)
              .collect(Collectors.toList());

      filesBundleParts.forEach(
          bundlePart -> {
            AppleBundleComponentCopySpec copySpec =
                new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
            copySpecs.add(copySpec);
          });
    }

    {
      List<DirectoryAppleBundlePart> directoryBundleParts =
          bundleParts.stream()
              .filter(p -> p instanceof DirectoryAppleBundlePart)
              .map(p -> (DirectoryAppleBundlePart) p)
              .collect(Collectors.toList());

      directoryBundleParts.forEach(
          bundlePart -> {
            AppleBundleComponentCopySpec copySpec =
                new AppleBundleComponentCopySpec(bundlePart, sourcePathResolver, destinations);
            copySpecs.add(copySpec);
          });
    }

    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-append-embedded-bundle-parts") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            if (shouldPerformIncrementalBuildSupplier.get()) {
              createEmbeddedBundlesIntermediateDirectories(
                  embeddedBundlesRelatedCopySpecsSupplier.get(), projectFilesystem);
              copySpecs.addAll(embeddedBundlesRelatedCopySpecsSupplier.get());
            } else {
              // Fallback to just copying bundles as a whole directories, it should be faster and
              // more robust
              copySpecs.addAll(
                  bundleParts.stream()
                      .filter(p -> p instanceof EmbeddedBundleAppleBundlePart)
                      .map(p -> (EmbeddedBundleAppleBundlePart) p)
                      .map(
                          p ->
                              SourcePathWithAppleBundleDestination.of(
                                  p.getSourcePath(), p.getDestination()))
                      .map(
                          s ->
                              new AppleBundleComponentCopySpec(s, sourcePathResolver, destinations))
                      .collect(Collectors.toList()));
            }
            return StepExecutionResults.SUCCESS;
          }
        });

    Stream.concat(
            resources.getResourceDirs().stream(),
            resources.getResourceFiles().stream()
                .filter(
                    pathWithDestination ->
                        !AppleProcessResources.shouldBeProcessed(
                            pathWithDestination, sourcePathResolver)))
        .forEach(
            pathWithDestination -> {
              AppleBundleComponentCopySpec copySpec =
                  new AppleBundleComponentCopySpec(
                      pathWithDestination, sourcePathResolver, destinations);
              copySpecs.add(copySpec);
            });

    stepsBuilder.add(
        new AbstractExecutionStep("apple-bundle-copy-non-processed-parts") {
          @Override
          public StepExecutionResult execute(StepExecutionContext context) throws IOException {
            if (shouldPerformIncrementalBuildSupplier.get()) {
              ImmutableSet.Builder<Path> pathsToDeleteBuilder = ImmutableSet.builder();
              ImmutableSet.Builder<AppleBundleComponentCopySpec> copySpecsBuilder =
                  ImmutableSet.builder();

              for (AppleBundleComponentCopySpec spec : copySpecs) {
                prepareCopyFileToBundleWithIncrementalSupport(
                    spec,
                    newContentHashesSupplier
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for current build should be computed when incremental build is performed"))
                        .get(),
                    oldContentHashesSupplier
                        .get()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "Content hashes for previous build should be computed when incremental build is performed")),
                    projectFilesystem,
                    dirRoot,
                    pathsToDeleteBuilder,
                    copySpecsBuilder);
              }
              for (Path pathToDelete : pathsToDeleteBuilder.build()) {
                projectFilesystem.deleteRecursivelyIfExists(pathToDelete);
              }
              for (AppleBundleComponentCopySpec spec : copySpecsBuilder.build()) {
                spec.performCopy(projectFilesystem, dirRoot);
              }

            } else {
              for (AppleBundleComponentCopySpec spec : copySpecs) {
                spec.performCopy(projectFilesystem, dirRoot);
              }
            }

            return StepExecutionResults.SUCCESS;
          }
        });
  }

  private static void createEmbeddedBundlesIntermediateDirectories(
      List<AppleBundleComponentCopySpec> appleBundleComponentCopySpecs,
      ProjectFilesystem projectFilesystem)
      throws IOException {
    for (RelPath relPath :
        appleBundleComponentCopySpecs.stream()
            .map(AppleBundleComponentCopySpec::getDestinationPathRelativeToBundleRoot)
            .map(RelPath::getParent)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet())) {
      projectFilesystem.createParentDirs(relPath.resolve("dummy"));
    }
  }

  /** Checks and throws an exception if parts of bundle have conflicting paths */
  public static void verifyResourceConflicts(
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      SourcePathResolverAdapter resolver,
      AppleBundleDestinations destinations) {
    // Ensure there are no resources that will overwrite each other
    // TODO: handle ResourceDirsContainingResourceDirs

    List<AppleBundleComponentCopySpec> copySpecs =
        Stream.concat(
                Stream.concat(
                        resources.getResourceDirs().stream(), resources.getResourceFiles().stream())
                    .map(e -> new AppleBundleComponentCopySpec(e, resolver, destinations)),
                Stream.concat(
                    Stream.concat(
                        bundleParts.stream()
                            .filter(p -> p instanceof DirectoryAppleBundlePart)
                            .map(p -> (DirectoryAppleBundlePart) p)
                            .map(e -> new AppleBundleComponentCopySpec(e, resolver, destinations)),
                        bundleParts.stream()
                            .filter(p -> p instanceof FileAppleBundlePart)
                            .map(p -> (FileAppleBundlePart) p)
                            .map(e -> new AppleBundleComponentCopySpec(e, resolver, destinations))),
                    bundleParts.stream()
                        .filter(p -> p instanceof EmbeddedBundleAppleBundlePart)
                        .map(p -> (EmbeddedBundleAppleBundlePart) p)
                        .map(
                            e ->
                                new AppleBundleComponentCopySpec(
                                    SourcePathWithAppleBundleDestination.of(
                                        e.getSourcePath(), e.getDestination()),
                                    resolver,
                                    destinations))))
            .collect(Collectors.toList());

    Map<RelPath, AbsPath> encounteredDestinationToSourcePaths = new HashMap<>();
    for (AppleBundleComponentCopySpec copySpec : copySpecs) {
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
}
