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

package com.facebook.buck.android;

import static com.google.common.collect.Ordering.natural;

import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.android.resources.MergeAndroidResources;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class MergeAndroidResourcesStep implements Step {
  private final SourcePathResolverAdapter pathResolver;
  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final ImmutableList<Path> uberRDotTxt;
  private final Path outputDir;
  private final boolean forceFinalResourceIds;
  private final EnumSet<RType> bannedDuplicateResourceTypes;
  private final Optional<Path> duplicateResourceWhitelistPath;
  private final Optional<String> unionPackage;
  private final ImmutableList<Path> overrideSymbolsPath;

  /**
   * Merges text symbols files from {@code aapt} for each of the input {@code android_resource} into
   * a set of resources per R.java package and writes an {@code R.java} file per package under the
   * output directory. Also, if {@code uberRDotTxt} is present, the IDs in the output {@code R.java}
   * file will be taken from the {@code R.txt} file.
   */
  @VisibleForTesting
  MergeAndroidResourcesStep(
      SourcePathResolverAdapter pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      ImmutableList<Path> uberRDotTxt,
      Path outputDir,
      boolean forceFinalResourceIds,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<Path> duplicateResourceWhitelistPath,
      ImmutableList<Path> overrideSymbolsPath,
      Optional<String> unionPackage) {
    this.pathResolver = pathResolver;
    this.androidResourceDeps = ImmutableList.copyOf(androidResourceDeps);
    this.uberRDotTxt = uberRDotTxt;
    this.outputDir = outputDir;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.duplicateResourceWhitelistPath = duplicateResourceWhitelistPath;
    this.unionPackage = unionPackage;
    this.overrideSymbolsPath = overrideSymbolsPath;
  }

  public static MergeAndroidResourcesStep createStepForDummyRDotJava(
      SourcePathResolverAdapter pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      Path outputDir,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage) {
    return new MergeAndroidResourcesStep(
        pathResolver,
        androidResourceDeps,
        /* uberRDotTxt */ ImmutableList.of(),
        outputDir,
        forceFinalResourceIds,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.empty(),
        ImmutableList.of(),
        unionPackage);
  }

  public static MergeAndroidResourcesStep createStepForUberRDotJava(
      SourcePathResolverAdapter pathResolver,
      List<HasAndroidResourceDeps> androidResourceDeps,
      ImmutableList<Path> uberRDotTxt,
      Path outputDir,
      EnumSet<RType> bannedDuplicateResourceTypes,
      Optional<Path> duplicateResourceWhitelistPath,
      ImmutableList<Path> overrideSymbolsPath,
      Optional<String> unionPackage) {
    return new MergeAndroidResourcesStep(
        pathResolver,
        androidResourceDeps,
        uberRDotTxt,
        outputDir,
        /* forceFinalResourceIds */ true,
        bannedDuplicateResourceTypes,
        duplicateResourceWhitelistPath,
        overrideSymbolsPath,
        unionPackage);
  }

  /** Returns R. java files */
  public ImmutableSortedSet<RelPath> getRDotJavaFiles(ProjectFilesystem projectFilesystem) {
    FluentIterable<String> packages =
        FluentIterable.from(
            unionPackage.map(Collections::singletonList).orElse(Collections.emptyList()));
    packages =
        packages.append(
            FluentIterable.from(androidResourceDeps)
                .transform(HasAndroidResourceDeps::getRDotJavaPackage));

    return packages
        .transform(
            p ->
                ProjectFilesystemUtils.relativize(
                    projectFilesystem.getRootPath(),
                    MergeAndroidResources.getPathToRDotJava(outputDir, p)))
        .toSortedSet(RelPath.comparator());
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) throws IOException {
    try {
      // In order to convert a symbols file to R.java, all resources of the same type are grouped
      // into a static class of that name. The static class contains static values that correspond
      // to the resource (type, name, value) tuples. See RDotTxtEntry.
      //
      // The first step is to merge symbol files of the same package type and resource type/name.
      // That is, within a package type, each resource type/name pair must be unique. If there are
      // multiple pairs, only one will be written to the R.java file.
      //
      // Because the resulting files do not match their respective resources.arsc, the values are
      // meaningless and do not represent the usable final result.  This is why the R.java file is
      // written without using final so that javac will not inline the values.
      ImmutableMap.Builder<Path, String> rDotTxtToPackage = ImmutableMap.builder();
      ImmutableMap.Builder<Path, String> symbolsFileToResourceDeps = ImmutableMap.builder();
      for (HasAndroidResourceDeps res : androidResourceDeps) {
        Path rDotTxtPath = pathResolver.getAbsolutePath(res.getPathToTextSymbolsFile()).getPath();
        rDotTxtToPackage.put(rDotTxtPath, res.getRDotJavaPackage());
        symbolsFileToResourceDeps.put(
            rDotTxtPath, res.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName());
      }

      MergeAndroidResources.mergeAndroidResources(
          uberRDotTxt,
          rDotTxtToPackage.build(),
          symbolsFileToResourceDeps.build(),
          forceFinalResourceIds,
          bannedDuplicateResourceTypes,
          duplicateResourceWhitelistPath,
          unionPackage,
          overrideSymbolsPath,
          outputDir,
          ImmutableList.of());

      return StepExecutionResults.SUCCESS;
    } catch (MergeAndroidResources.DuplicateResourceException e) {
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setStderr(Optional.of(e.getMessage()))
          .build();
    }
  }

  @Override
  public String getShortName() {
    return "android-res-merge";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    List<String> resources =
        androidResourceDeps.stream()
            .map(Object::toString)
            .sorted(natural())
            .collect(Collectors.toList());
    return getShortName() + " " + Joiner.on(' ').join(resources);
  }
}
