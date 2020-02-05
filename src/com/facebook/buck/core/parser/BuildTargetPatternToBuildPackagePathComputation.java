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

package com.facebook.buck.core.parser;

import com.facebook.buck.core.files.DirectoryList;
import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.DirectoryListKey;
import com.facebook.buck.core.files.FileTree;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.files.FileTreeFileNameIterator;
import com.facebook.buck.core.files.FileTreeKey;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterators;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * Discover paths for packages which contain targets that match specification (build target pattern)
 *
 * <p>This computation depends on {@link FileTreeKey} ({@link FileTreeComputation}) and {@link
 * DirectoryListKey} ({@link DirectoryListComputation}).
 *
 * <p>See {@link WatchmanBuildPackageComputation} for an equivalent computation which uses Watchman.
 */
public class BuildTargetPatternToBuildPackagePathComputation
    implements GraphComputation<BuildTargetPatternToBuildPackagePathKey, BuildPackagePaths> {
  private final String buildFileName;
  private final FileSystem filesystem;

  private BuildTargetPatternToBuildPackagePathComputation(
      String buildFileName, ProjectFilesystemView projectFilesystemView) {
    this.buildFileName = buildFileName;
    this.filesystem = projectFilesystemView.getRootPath().getFileSystem();
  }

  /**
   * Create new instance of {@link BuildTargetPatternToBuildPackagePathComputation}
   *
   * @param buildFileName Name of the build file to determine a package root folder, for example
   *     'BUCK'
   */
  public static BuildTargetPatternToBuildPackagePathComputation of(
      String buildFileName, ProjectFilesystemView projectFilesystemView) {
    return new BuildTargetPatternToBuildPackagePathComputation(
        buildFileName, projectFilesystemView);
  }

  @Override
  public ComputationIdentifier<BuildPackagePaths> getIdentifier() {
    return BuildTargetPatternToBuildPackagePathKey.IDENTIFIER;
  }

  @Override
  public BuildPackagePaths transform(
      BuildTargetPatternToBuildPackagePathKey key, ComputationEnvironment env) {

    Path basePath = key.getPattern().getCellRelativeBasePath().getPath().toPath(filesystem);
    ImmutableSortedSet<Path> packageRoots;
    switch (key.getPattern().getKind()) {
      case SINGLE:
      case PACKAGE:
        // Patterns like "//package:target" or "//package:" match targets from one build package
        // only, so we only determine if build file is in the appropriate folder and return one
        // element if it is, empty collection if it is not
        DirectoryList dirList = env.getDep(DirectoryListKey.of(basePath));

        Path relativeBuildFilePath = basePath.resolve(buildFileName);

        packageRoots =
            dirList.getFiles().contains(relativeBuildFilePath)
                    || dirList.getSymlinks().contains(relativeBuildFilePath)
                ? ImmutableSortedSet.of(basePath)
                : ImmutableSortedSet.of();
        break;
      case RECURSIVE:
        // Patterns like //package/... match targets from all packages below certain folder, so
        // traverse file tree down and get all paths that have build file in them
        FileTree fileTree = env.getDep(FileTreeKey.of(basePath));

        // FileTreeFileNameIterator returns all paths in the directory tree which file name is
        // buildFileName
        // Strip out buildFileName to get a path pointing to package root folder
        // All paths are relative
        packageRoots =
            ImmutableSortedSet.copyOf(
                Iterators.transform(
                    FileTreeFileNameIterator.of(fileTree, buildFileName),
                    path -> MorePaths.getParentOrEmpty(path)));
        break;
      default:
        throw new IllegalStateException();
    }

    return ImmutableBuildPackagePaths.of(packageRoots);
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      BuildTargetPatternToBuildPackagePathKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      BuildTargetPatternToBuildPackagePathKey key) {
    switch (key.getPattern().getKind()) {
      case SINGLE:
      case PACKAGE:
        // For patterns like "//package:target" or "//package:" we only need a listing of a
        // particular directory to determine if build file is there or not
        return ImmutableSet.of(
            DirectoryListKey.of(
                key.getPattern().getCellRelativeBasePath().getPath().toPath(filesystem)));
      case RECURSIVE:
        // For patterns like "//package/..." we need a complete directory tree structure to discover
        // all packages recursively
        return ImmutableSet.of(
            FileTreeKey.of(
                key.getPattern().getCellRelativeBasePath().getPath().toPath(filesystem)));
    }
    throw new IllegalStateException();
  }
}
