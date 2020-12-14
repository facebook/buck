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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A helper class for {@link RobolectricTest} and {@link RobolectricTestX} */
class RobolectricTestHelper {
  private static final Logger LOG = Logger.get(RobolectricTestHelper.class);

  /**
   * Used by robolectric test runner to get list of resource directories that can be used for tests.
   */
  static final String LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_res_directories";

  static final String LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME =
      "buck.robolectric_assets_directories";

  static final String ROBOLECTRIC_MANIFEST = "buck.robolectric_manifest";

  static final String ROBOLECTRIC_DEPENDENCY_DIR = "robolectric.dependency.dir";

  private final BuildTarget buildTarget;
  private final Optional<DummyRDotJava> optionalDummyRDotJava;
  private final Optional<Aapt2Link> optionalAapt2Link;
  private final Optional<SourcePath> robolectricManifest;
  private final Optional<SourcePath> robolectricRuntimeDependency;
  private final ProjectFilesystem projectFilesystem;
  private final boolean passDirectoriesInFile;
  private final RelPath resourceDirectoriesPath;
  private final RelPath assetDirectoriesPath;

  RobolectricTestHelper(
      BuildTarget buildTarget,
      Optional<DummyRDotJava> optionalDummyRDotJava,
      Optional<Aapt2Link> optionalAapt2Link,
      Optional<SourcePath> robolectricRuntimeDependency,
      Optional<SourcePath> robolectricManifest,
      ProjectFilesystem projectFilesystem,
      boolean passDirectoriesInFile) {
    this.buildTarget = buildTarget;
    this.optionalDummyRDotJava = optionalDummyRDotJava;
    this.optionalAapt2Link = optionalAapt2Link;
    this.robolectricRuntimeDependency = robolectricRuntimeDependency;
    this.robolectricManifest = robolectricManifest;
    this.projectFilesystem = projectFilesystem;
    this.passDirectoriesInFile = passDirectoriesInFile;

    resourceDirectoriesPath =
        RobolectricTestHelper.getResourceDirectoriesPath(projectFilesystem, buildTarget);
    assetDirectoriesPath =
        RobolectricTestHelper.getAssetDirectoriesPath(projectFilesystem, buildTarget);
  }

  @VisibleForTesting
  static RelPath getResourceDirectoriesPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(
        projectFilesystem.getBuckPaths(), buildTarget, "%s/robolectric-resource-directories");
  }

  @VisibleForTesting
  static RelPath getAssetDirectoriesPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(
        projectFilesystem.getBuckPaths(), buildTarget, "%s/robolectric-asset-directories");
  }

  private String getDirectoriesContent(
      SourcePathResolverAdapter pathResolver, Function<HasAndroidResourceDeps, SourcePath> filter) {
    String content;
    if (optionalDummyRDotJava.isPresent()) {
      Iterable<String> resourceDirectories =
          getDirs(
              optionalDummyRDotJava.get().getAndroidResourceDeps().stream().map(filter),
              pathResolver);
      content = Joiner.on('\n').join(resourceDirectories);
    } else {
      content = "";
    }
    return content;
  }

  /** Write resource and asset before test */
  void onPreTest(BuildContext buildContext) throws IOException {
    projectFilesystem.writeContentsToPath(
        getDirectoriesContent(buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getRes),
        resourceDirectoriesPath);
    projectFilesystem.writeContentsToPath(
        getDirectoriesContent(
            buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getAssets),
        assetDirectoriesPath);
  }

  void addPreTestSteps(BuildContext buildContext, ImmutableList.Builder<Step> stepsBuilder) {
    stepsBuilder.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), projectFilesystem, resourceDirectoriesPath)));
    stepsBuilder.add(
        WriteFileStep.of(
            projectFilesystem.getRootPath(),
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getRes),
            resourceDirectoriesPath,
            false));
    stepsBuilder.add(
        WriteFileStep.of(
            projectFilesystem.getRootPath(),
            getDirectoriesContent(
                buildContext.getSourcePathResolver(), HasAndroidResourceDeps::getAssets),
            assetDirectoriesPath,
            false));
  }

  /** Amend jvm args, adding manifest and dependency paths */
  void amendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder, SourcePathResolverAdapter pathResolver) {
    if (optionalDummyRDotJava.isPresent()) {
      ImmutableList<HasAndroidResourceDeps> resourceDeps =
          optionalDummyRDotJava.get().getAndroidResourceDeps();
      vmArgsBuilder.add(getRobolectricResourceDirectoriesArg(pathResolver, resourceDeps));
      vmArgsBuilder.add(getRobolectricAssetsDirectories(pathResolver, resourceDeps));
    }

    // Force robolectric to only use local dependency resolution.
    vmArgsBuilder.add("-Drobolectric.offline=true");
    robolectricManifest.ifPresent(
        s ->
            vmArgsBuilder.add(
                String.format(
                    "-D%s=%s",
                    RobolectricTestHelper.ROBOLECTRIC_MANIFEST, pathResolver.getAbsolutePath(s))));
    robolectricRuntimeDependency.ifPresent(
        s ->
            vmArgsBuilder.add(
                String.format(
                    "-D%s=%s",
                    RobolectricTestHelper.ROBOLECTRIC_DEPENDENCY_DIR,
                    pathResolver.getAbsolutePath(s))));
  }

  @VisibleForTesting
  String getRobolectricAssetsDirectories(
      SourcePathResolverAdapter pathResolver, List<HasAndroidResourceDeps> resourceDeps) {
    String argValue;
    if (passDirectoriesInFile) {
      argValue = "@" + projectFilesystem.resolve(assetDirectoriesPath);
    } else {
      argValue =
          Joiner.on(File.pathSeparator)
              .join(
                  getDirs(
                      resourceDeps.stream().map(HasAndroidResourceDeps::getAssets), pathResolver));
    }

    return String.format(
        "-D%s=%s", RobolectricTestHelper.LIST_OF_ASSETS_DIRECTORIES_PROPERTY_NAME, argValue);
  }

  @VisibleForTesting
  String getRobolectricResourceDirectoriesArg(
      SourcePathResolverAdapter pathResolver, List<HasAndroidResourceDeps> resourceDeps) {
    String argValue;
    if (passDirectoriesInFile) {
      argValue = "@" + projectFilesystem.resolve(resourceDirectoriesPath);
    } else {
      argValue =
          Joiner.on(File.pathSeparator)
              .join(
                  getDirs(resourceDeps.stream().map(HasAndroidResourceDeps::getRes), pathResolver));
    }

    return String.format(
        "-D%s=%s", RobolectricTestHelper.LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME, argValue);
  }

  private Iterable<String> getDirs(
      Stream<SourcePath> sourcePathStream, SourcePathResolverAdapter pathResolver) {

    return sourcePathStream
        .filter(Objects::nonNull)
        .map(input -> projectFilesystem.relativize(pathResolver.getAbsolutePath(input)))
        .filter(
            input -> {
              try {
                if (!projectFilesystem.isDirectory(input)) {
                  throw new RuntimeException(
                      String.format(
                          "Path %s is needed to run robolectric test %s, but was not found.",
                          input, buildTarget));
                }
                return !projectFilesystem.getDirectoryContents(input.getPath()).isEmpty();
              } catch (IOException e) {
                LOG.warn(e, "Error filtering path for Robolectric res/assets.");
                return true;
              }
            })
        .map(Object::toString)
        .collect(Collectors.toList());
  }

  /** get extra run time dependency defined in the test description */
  Stream<BuildTarget> getExtraRuntimeDeps(
      BuildRuleResolver buildRuleResolver, SortedSet<BuildRule> buildDeps) {
    return Stream.of(
            // On top of the runtime dependencies of a normal {@link JavaTest}, we need to make
            // the
            // {@link DummyRDotJava} and any of its resource deps is available locally (if it
            // exists)
            // to run this test.
            RichStream.from(optionalDummyRDotJava),
            buildRuleResolver.filterBuildRuleInputs(
                RichStream.from(optionalDummyRDotJava)
                    .flatMap(input -> input.getAndroidResourceDeps().stream())
                    .flatMap(input -> Stream.of(input.getRes(), input.getAssets()))
                    .filter(Objects::nonNull)),
            // We need the aapt2link if we're using binary resources
            RichStream.from(optionalAapt2Link),
            // It's possible that the user added some tool as a dependency, so make sure we
            // promote this rules first-order deps to runtime deps, so that these potential
            // tools are available when this test runs.
            buildDeps.stream())
        .reduce(Stream.empty(), Stream::concat)
        .map(BuildRule::getBuildTarget);
  }

  protected ImmutableSet<Path> getExtraRequiredPaths(
      SourcePathResolverAdapter sourcePathResolverAdapter) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    optionalAapt2Link.ifPresent(
        link ->
            builder.add(
                sourcePathResolverAdapter
                    .getAbsolutePath(link.getAaptOutputInfo().getPrimaryResourcesApkPath())
                    .getPath()));
    robolectricManifest.ifPresent(
        robolectricManifest ->
            builder.add(sourcePathResolverAdapter.getAbsolutePath(robolectricManifest).getPath()));
    builder.add(projectFilesystem.resolve(resourceDirectoriesPath).getPath());
    builder.add(projectFilesystem.resolve(assetDirectoriesPath).getPath());

    return builder.build();
  }
}
