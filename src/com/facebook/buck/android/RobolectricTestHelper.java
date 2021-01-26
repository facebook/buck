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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.stream.Stream;

/** A helper class for {@link RobolectricTest} */
class RobolectricTestHelper {
  static final String ROBOLECTRIC_MANIFEST = "buck.robolectric_manifest";

  static final String ROBOLECTRIC_DEPENDENCY_DIR = "robolectric.dependency.dir";

  private final MergeAssets binaryResources;
  private final SourcePath robolectricManifest;
  private final Optional<SourcePath> robolectricRuntimeDependency;
  private final ProjectFilesystem projectFilesystem;

  RobolectricTestHelper(
      MergeAssets binaryResources,
      Optional<SourcePath> robolectricRuntimeDependency,
      SourcePath robolectricManifest,
      ProjectFilesystem projectFilesystem) {
    this.binaryResources = binaryResources;
    this.robolectricRuntimeDependency = robolectricRuntimeDependency;
    this.robolectricManifest = robolectricManifest;
    this.projectFilesystem = projectFilesystem;
  }

  /** Amend jvm args, adding manifest and dependency paths */
  void amendVmArgs(
      ImmutableList.Builder<String> vmArgsBuilder, SourcePathResolverAdapter pathResolver) {
    // Force robolectric to only use local dependency resolution.
    vmArgsBuilder.add("-Drobolectric.offline=true");

    vmArgsBuilder.add(
        String.format(
            "-D%s=%s",
            RobolectricTestHelper.ROBOLECTRIC_MANIFEST,
            pathResolver.getAbsolutePath(robolectricManifest)));
    robolectricRuntimeDependency.ifPresent(
        s ->
            vmArgsBuilder.add(
                String.format(
                    "-D%s=%s",
                    RobolectricTestHelper.ROBOLECTRIC_DEPENDENCY_DIR,
                    pathResolver.getAbsolutePath(s))));
  }

  /** get extra run time dependency defined in the test description */
  Stream<BuildTarget> getExtraRuntimeDeps(SortedSet<BuildRule> buildDeps) {
    return Stream.of(
            // We need the binary resources.
            RichStream.of(binaryResources),
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
    builder.add(
        sourcePathResolverAdapter
            .getAbsolutePath(binaryResources.getSourcePathToOutput())
            .getPath());
    builder.add(sourcePathResolverAdapter.getAbsolutePath(robolectricManifest).getPath());

    robolectricRuntimeDependency.ifPresent(
        robolectricRuntimeDir -> {
          Path robolectricRuntimeDirPath =
              sourcePathResolverAdapter.getAbsolutePath(robolectricRuntimeDir).getPath();
          ImmutableCollection<Path> relativePaths;
          try {
            relativePaths =
                projectFilesystem.asView().getDirectoryContents(robolectricRuntimeDirPath);
          } catch (IOException e) {
            throw new RuntimeException(
                "Unable to get directory contents for " + robolectricRuntimeDir, e);
          }
          builder.addAll(
              relativePaths.stream()
                  .map(projectFilesystem::resolve)
                  .collect(ImmutableSet.toImmutableSet()));
        });

    return builder.build();
  }
}
