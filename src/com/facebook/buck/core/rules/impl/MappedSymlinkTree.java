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
package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;

/**
 * A specialization of {@link SymlinkTree} exclusively for legacy cases that use static {@link
 * java.util.Map}s to model symlinks, and which need access to the {@link
 * MappedSymlinkTree#getLinks()} getter for subsequent access to the above map.
 */
public class MappedSymlinkTree extends SymlinkTree {

  private final ImmutableSortedMap<Path, SourcePath> links;

  public MappedSymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableSortedMap<Path, SourcePath> links) {
    super(category, target, filesystem, root, links);
    this.links = links;
  }

  public MappedSymlinkTree(
      String category,
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    this(category, target, filesystem, root, ImmutableSortedMap.copyOf(links));
  }

  public ImmutableSortedMap<Path, SourcePath> getLinks() {
    return links;
  }
}
