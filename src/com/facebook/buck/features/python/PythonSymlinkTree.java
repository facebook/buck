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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.SymlinkDir;
import com.facebook.buck.core.rules.impl.SymlinkMap;
import com.facebook.buck.core.rules.impl.SymlinkPack;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.impl.Symlinks;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/** Creates a tree of symlinks inside of a given directory */
public class PythonSymlinkTree extends SymlinkTree {
  private static final Path INIT_PY = Paths.get("__init__.py");

  /**
   * Creates an instance of {@link SymlinkTree}
   *
   * @param category A name used in the symlink steps
   * @param target The target for this rule
   * @param filesystem The filesystem that the tree lives on
   * @param root The directory to create symlinks in
   * @param links A map of path within the link tree to the target of the symlikm
   * @param directoriesToMerge A map of relative paths within the link tree into which files from
   *     the value will be recursively linked. e.g. if a file at /tmp/foo/bar should be linked as
   *     /tmp/symlink-root/subdir/bar, the map should contain {Paths.get("subdir"),
   *     SourcePath(Paths.get("tmp", "foo")) }
   * @param ruleFinder Used to iterate over {@code directoriesToMerge} in order get the build time
   */
  public PythonSymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      ImmutableSortedSet<SourcePath> directoriesToMerge,
      SourcePathRuleFinder ruleFinder) {
    super(
        category,
        target,
        filesystem,
        ruleFinder,
        root,
        new SymlinkPack(
            ImmutableList.<Symlinks>builder()
                .add(new SymlinkMap(links))
                .addAll(
                    directoriesToMerge.stream().map(SymlinkDir::new).collect(Collectors.toList()))
                .build()));
  }

  @Override
  protected boolean shouldDeleteExistingSymlink(
      ProjectFilesystem projectFilesystem, Path existingTarget) {
    // If the existing __init__.py already exists, and is 0 bytes, it should be safe to replace
    // with a more substantial file that has real functionality
    try {
      return existingTarget.getFileName().equals(INIT_PY)
          && projectFilesystem.getFileSize(existingTarget) == 0;
    } catch (IOException e) {
      return false;
    }
  }
}
