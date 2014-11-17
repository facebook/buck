/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.io;

import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Test implementation of {@link DirectoryTraverser} which emulates traversal
 * over zero or more (file, path) pairs given a root path.
 */
public class FakeDirectoryTraverser implements DirectoryTraverser {

  /**
   * A (file, path) pair passed to {@link DirectoryTraversal#visit(File, String)}.
   */
  public static class Entry {
    public final File file;
    public final String path;

    public Entry(@Nullable File file, String path) {
      this.file = file;
      this.path = path;
    }
  }

  private final ImmutableMap<String, Collection<Entry>> pathsToEntries;

  /**
   * Creates an empty FakeDirectoryTraverser which throws an exception
   * any time it's asked to traverse a traversal.
   */
  public FakeDirectoryTraverser() {
    this.pathsToEntries = ImmutableMap.of();
  }

  /**
   * Creates a FakeDirectoryTraverser which emulates traversal over a collection
   * of (file, path) pairs for each root path key in the map.
   */
  public FakeDirectoryTraverser(Map<String, Collection<Entry>> pathsToEntries) {
    this.pathsToEntries = ImmutableMap.copyOf(pathsToEntries);
  }

  @Override
  public void traverse(DirectoryTraversal traversal) throws IOException {
    String traversalRootPath = MorePaths.pathWithUnixSeparators(traversal.getRoot().toPath());
    Collection<Entry> entries = pathsToEntries.get(traversalRootPath);
    if (entries == null) {
      throw new RuntimeException("Unexpected: no traversal for root path: " + traversalRootPath);
    }
    for (Entry entry : entries) {
      traversal.visit(entry.file, entry.path);
    }
  }
}
