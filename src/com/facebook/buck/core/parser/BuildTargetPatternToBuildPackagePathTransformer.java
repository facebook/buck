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

package com.facebook.buck.core.parser;

import com.facebook.buck.core.files.DirectoryList;
import com.facebook.buck.core.files.FileTree;
import com.facebook.buck.core.files.FileTreeFileNameIterator;
import com.facebook.buck.core.files.ImmutableDirectoryListKey;
import com.facebook.buck.core.files.ImmutableFileTreeKey;
import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterators;
import java.nio.file.Path;

/**
 * Discover paths for packages which contain targets that match specification (build target pattern)
 */
public class BuildTargetPatternToBuildPackagePathTransformer
    implements GraphComputation<BuildTargetPatternToBuildPackagePathKey, BuildPackagePaths> {

  private final String buildFileName;

  private BuildTargetPatternToBuildPackagePathTransformer(String buildFileName) {
    this.buildFileName = buildFileName;
  }

  /**
   * Create new instance of {@link BuildTargetPatternToBuildPackagePathTransformer}
   *
   * @param buildFileName Name of the build file to determine a package root folder, for example
   *     'BUCK'
   */
  public static BuildTargetPatternToBuildPackagePathTransformer of(String buildFileName) {
    return new BuildTargetPatternToBuildPackagePathTransformer(buildFileName);
  }

  @Override
  public Class<BuildTargetPatternToBuildPackagePathKey> getKeyClass() {
    return BuildTargetPatternToBuildPackagePathKey.class;
  }

  @Override
  public BuildPackagePaths transform(
      BuildTargetPatternToBuildPackagePathKey key, ComputationEnvironment env) {

    Path basePath = key.getPattern().getBasePath();
    ImmutableSortedSet<Path> packageRoots;
    switch (key.getPattern().getKind()) {
      case SINGLE:
      case PACKAGE:
        // Patterns like "//package:target" or "//package:" match targets from one build package
        // only, so we only determine if build file is in the appropriate folder and return one
        // element if it is, empty collection if it is not
        DirectoryList dirList = env.getDep(ImmutableDirectoryListKey.of(basePath));
        packageRoots =
            dirList.getFiles().contains(basePath.resolve(buildFileName))
                ? ImmutableSortedSet.of(basePath)
                : ImmutableSortedSet.of();
        break;
      case RECURSIVE:
        // Patterns like //package/... match targets from all packages below certain folder, so
        // traverse file tree down and get all paths that have build file in them
        FileTree fileTree = env.getDep(ImmutableFileTreeKey.of(basePath));

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
        return ImmutableSet.of(ImmutableDirectoryListKey.of(key.getPattern().getBasePath()));
      case RECURSIVE:
        // For patterns like "//package/..." we need a complete directory tree structure to discover
        // all packages recursively
        return ImmutableSet.of(ImmutableFileTreeKey.of(key.getPattern().getBasePath()));
    }
    throw new IllegalStateException();
  }
}
