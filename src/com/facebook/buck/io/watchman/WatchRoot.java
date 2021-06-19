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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/** Watchman watch root, which is an absolute path. */
public class WatchRoot {
  private final String root;

  public WatchRoot(String root) {
    Preconditions.checkArgument(root != null);
    Preconditions.checkArgument(
        Paths.get(root).isAbsolute(), "watch root must be absolute: %s", root);
    this.root = root;
  }

  /** Do not validate given string is correct path. Used in tests. */
  @VisibleForTesting
  WatchRoot(String root, boolean unchecked) {
    Preconditions.checkArgument(root != null);
    Preconditions.checkArgument(unchecked);
    this.root = root;
  }

  public WatchRoot(AbsPath root) {
    Preconditions.checkArgument(root != null);
    this.root = root.toString();
  }

  public WatchRoot(Path root) {
    this(AbsPath.of(root));
  }

  /** Convert to path object of given filesystem. */
  public AbsPath toPath(FileSystem fileSystem) {
    return AbsPath.of(fileSystem.getPath(root));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    WatchRoot watchRoot = (WatchRoot) o;
    return root.equals(watchRoot.root);
  }

  @Override
  public int hashCode() {
    return Objects.hash(root);
  }

  @Override
  public String toString() {
    return root;
  }
}
