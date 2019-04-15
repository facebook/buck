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
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

/** Produce a list of files and directories in provided folder */
public class DirectoryListComputation implements GraphComputation<DirectoryListKey, DirectoryList> {

  private final ProjectFilesystemView fileSystemView;

  private DirectoryListComputation(ProjectFilesystemView fileSystemView) {
    this.fileSystemView = fileSystemView;
  }

  public static DirectoryListComputation of(ProjectFilesystemView fileSystemView) {
    return new DirectoryListComputation(fileSystemView);
  }

  @Override
  public Class<DirectoryListKey> getKeyClass() {
    return DirectoryListKey.class;
  }

  @Override
  public DirectoryList transform(DirectoryListKey key, ComputationEnvironment env)
      throws Exception {
    ImmutableCollection<Path> contents = fileSystemView.getDirectoryContents(key.getPath());

    ImmutableSortedSet.Builder<Path> filesBuilder =
        new ImmutableSortedSet.Builder<>(Comparator.naturalOrder());
    ImmutableSortedSet.Builder<Path> dirsBuilder =
        new ImmutableSortedSet.Builder<>(Comparator.naturalOrder());
    ImmutableSortedSet.Builder<Path> symlinksBuilder =
        new ImmutableSortedSet.Builder<>(Comparator.naturalOrder());

    for (Path p : contents) {
      BasicFileAttributes attrs =
          fileSystemView.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

      if (attrs.isDirectory()) {
        dirsBuilder.add(p);
        continue;
      }

      if (attrs.isRegularFile()) {
        filesBuilder.add(p);
        continue;
      }

      if (attrs.isSymbolicLink()) {
        symlinksBuilder.add(p);
        continue;
      }
    }

    return ImmutableDirectoryList.of(
        filesBuilder.build(), dirsBuilder.build(), symlinksBuilder.build());
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverDeps(
      DirectoryListKey key, ComputationEnvironment env) {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<? extends ComputeKey<? extends ComputeResult>> discoverPreliminaryDeps(
      DirectoryListKey key) {
    return ImmutableSet.of();
  }
}
