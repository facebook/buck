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
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      SourcePath processedResourcesDir,
      Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier,
      Optional<Supplier<ImmutableMap<RelPath, String>>> newContentHashesSupplier) {
    addStepsToCreateDirectoriesWhereBundlePartsAreCopied(
        context, stepsBuilder, resources, bundleParts, dirRoot, destinations, projectFilesystem);
    addStepsToCopyContentOfDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        resources,
        bundleParts,
        dirRoot,
        destinations,
        projectFilesystem,
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
        oldContentHashesSupplier,
        newContentHashesSupplier);
    addStepsToCopyNonProcessedFilesAndDirectories(
        context.getSourcePathResolver(),
        stepsBuilder,
        codeSignOnCopyPathsBuilder,
        resources,
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

  private static void addStepsToCopyContentOfDirectories(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier,
      Optional<Supplier<ImmutableMap<RelPath, String>>> newContentHashesSupplier) {

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

              copyContentsOfDirectoryIfNeededToBundleWithIncrementalSupport(
                  resolvedSourcePath,
                  destinationDirectoryPath,
                  dirRoot,
                  projectFilesystem,
                  oldContentHashesSupplier,
                  newContentHashesSupplier);
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
      Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier,
      Optional<Supplier<ImmutableMap<RelPath, String>>> newContentHashesSupplier) {

    ImmutableSet.Builder<Path> pathsToDeleteBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<AppleBundleComponentCopySpec> copySpecsBuilder = ImmutableSet.builder();

    for (String fileName :
        Objects.requireNonNull(new File(sourceContainerDirectoryPath.toUri()).list())) {
      AbsPath fromPath = sourceContainerDirectoryPath.resolve(fileName);
      RelPath toPathRelativeToBundleRoot = destinationDirectoryPath.resolveRel(fileName);
      prepareCopyFileToBundleWithIncrementalSupport(
          fromPath,
          toPathRelativeToBundleRoot,
          newContentHashesSupplier,
          oldContentHashesSupplier,
          dirRoot,
          pathsToDeleteBuilder,
          copySpecsBuilder);
    }

    pathsToDeleteBuilder
        .build()
        .parallelStream()
        .forEach(
            path -> {
              try {
                projectFilesystem.deleteRecursivelyIfExists(path);
              } catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
              }
            });

    copySpecsBuilder
        .build()
        .parallelStream()
        .forEach(
            spec -> {
              try {
                spec.performCopy(projectFilesystem, dirRoot);
              } catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
              }
            });
  }

  private static void prepareCopyFileToBundleWithIncrementalSupport(
      AbsPath fromPath,
      RelPath toPathRelativeToBundleRoot,
      Optional<Supplier<ImmutableMap<RelPath, String>>> newContentHashesSupplier,
      Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier,
      Path dirRoot,
      ImmutableSet.Builder<Path> pathsToDeleteBuilder,
      ImmutableSet.Builder<AppleBundleComponentCopySpec> copySpecsBuilder) {
    if (newContentHashesSupplier.isPresent()) {
      String oldHash = oldContentHashesSupplier.get().get(toPathRelativeToBundleRoot);
      String newHash = newContentHashesSupplier.get().get().get(toPathRelativeToBundleRoot);
      Preconditions.checkState(
          newHash != null, "Expecting a hash to be computed when incremental bundling is enabled");
      if (oldHash != null && oldHash.equals(newHash)) {
        return;
      }
      pathsToDeleteBuilder.add(dirRoot.resolve(toPathRelativeToBundleRoot.getPath()));
    }
    copySpecsBuilder.add(
        new AppleBundleComponentCopySpec(fromPath, toPathRelativeToBundleRoot, false));
  }

  private static void addStepsToCopyProcessedResources(
      SourcePathResolverAdapter sourcePathResolver,
      ImmutableList.Builder<Step> stepsBuilder,
      AppleBundleResources resources,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem,
      SourcePath processedResourcesDir,
      Supplier<ImmutableMap<RelPath, String>> oldContentHashesSupplier,
      Optional<Supplier<ImmutableMap<RelPath, String>>> newContentHashesSupplier) {
    Set<AppleBundleDestination> destinationsForAllProcessedResources = new HashSet<>();
    {
      if (resources.getResourceVariantFiles().size() > 0) {
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
              copyContentsOfDirectoryIfNeededToBundleWithIncrementalSupport(
                  processedResourcesContainerDirForDestination,
                  RelPath.of(destination.getPath(destinations)),
                  dirRoot,
                  projectFilesystem,
                  oldContentHashesSupplier,
                  newContentHashesSupplier);
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
      ImmutableList.Builder<Path> codeSignOnCopyPathsBuilder,
      AppleBundleResources resources,
      ImmutableList<AppleBundlePart> bundleParts,
      Path dirRoot,
      AppleBundleDestinations destinations,
      ProjectFilesystem projectFilesystem) {

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

            if (bundlePart.getCodesignOnCopy()) {
              Path toPath =
                  dirRoot.resolve(copySpec.getDestinationPathRelativeToBundleRoot().getPath());
              codeSignOnCopyPathsBuilder.add(toPath);
            }
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

            if (bundlePart.getCodesignOnCopy()) {
              Path toPath =
                  dirRoot.resolve(copySpec.getDestinationPathRelativeToBundleRoot().getPath());
              codeSignOnCopyPathsBuilder.add(toPath);
            }
          });
    }

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

              if (pathWithDestination.getCodesignOnCopy()) {
                Path toPath =
                    dirRoot.resolve(copySpec.getDestinationPathRelativeToBundleRoot().getPath());
                codeSignOnCopyPathsBuilder.add(toPath);
              }
            });

    stepsBuilder.addAll(
        copySpecs.stream()
            .map(spec -> spec.createCopyStep(projectFilesystem, dirRoot))
            .collect(Collectors.toList()));
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
                    bundleParts.stream()
                        .filter(p -> p instanceof DirectoryAppleBundlePart)
                        .map(p -> (DirectoryAppleBundlePart) p)
                        .map(e -> new AppleBundleComponentCopySpec(e, resolver, destinations)),
                    bundleParts.stream()
                        .filter(p -> p instanceof FileAppleBundlePart)
                        .map(p -> (FileAppleBundlePart) p)
                        .map(e -> new AppleBundleComponentCopySpec(e, resolver, destinations))))
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
