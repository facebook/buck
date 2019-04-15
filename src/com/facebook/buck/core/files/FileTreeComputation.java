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

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.ComputationEnvironment;
import com.facebook.buck.core.graph.transformation.GraphComputation;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.util.MoreMaps;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

/**
 * Produce a file tree of a provided folder, i.e. a list of files and dependent folders recursively
 */
public class FileTreeComputation implements GraphComputation<FileTreeKey, FileTree> {

  private FileTreeComputation() {}

  public static FileTreeComputation of() {
    return new FileTreeComputation();
  }

  @Override
  public Class<FileTreeKey> getKeyClass() {
    return FileTreeKey.class;
  }

  @Override
  public FileTree transform(FileTreeKey key, ComputationEnvironment env) {

    DirectoryList currentDir = env.getDep(ImmutableDirectoryListKey.of(key.getPath()));
    ImmutableMap<FileTreeKey, FileTree> children = env.getDeps(FileTreeKey.class);

    ImmutableMap<Path, FileTree> deps;
    if (children.isEmpty()) {
      deps = ImmutableMap.of();
    } else {
      // Convert Map<FileTreeKey, FileTree> to Map<Path, FileTree>
      // Not using streams etc. for performance
      // May be instead have FileTree object to have Map<FileTreeKey, FileTree> instead of
      // Map<Path, FileTree>? The interface is less cleaner then, but we can reuse collections

      deps = MoreMaps.transformKeys(children, k -> k.getPath());
    }

    return ImmutableFileTree.of(key.getPath(), currentDir, deps);
  }

  @Override
  public ImmutableSet<FileTreeKey> discoverDeps(FileTreeKey key, ComputationEnvironment env) {
    DirectoryList currentDir = env.getDep(ImmutableDirectoryListKey.of(key.getPath()));
    ImmutableSortedSet<Path> subDirs = currentDir.getDirectories();
    if (subDirs.isEmpty()) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<FileTreeKey> depsBuilder =
        ImmutableSet.builderWithExpectedSize(subDirs.size());

    for (Path path : subDirs) {
      depsBuilder.add(ImmutableFileTreeKey.of(path));
    }

    return depsBuilder.build();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      FileTreeKey key) {
    return ImmutableSet.of(ImmutableDirectoryListKey.of(key.getPath()));
  }
}
