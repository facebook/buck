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

package com.facebook.buck.core.filesystems;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Relative path. */
public interface RelPath extends PathWrapper {
  /**
   * Cosnstruct using {@link java.nio.file.Path} object.
   *
   * <p>Note this operation is just a cast if the path is {@link BuckUnixPath}.
   *
   * @throws RuntimeException the path is not absolute.
   */
  static RelPath of(Path path) {
    if (path instanceof AbsPath) {
      return (RelPath) path;
    } else {
      return new RelPathImpl(path);
    }
  }

  /**
   * Construct a path.
   *
   * @throws RuntimeException if the path is absolute.
   */
  static RelPath get(String first, String... more) {
    return of(Paths.get(first, more));
  }

  /** Behaves exactly like {@link Path#normalize()}. */
  default RelPath normalize() {
    return of(getPath().normalize());
  }

  default RelPath getParent() {
    Path parent = getPath().getParent();
    return parent != null ? RelPath.of(parent) : null;
  }

  default Path resolve(String other) {
    return getPath().resolve(other);
  }

  default RelPath resolveRel(String other) {
    return RelPath.of(resolve(other));
  }

  default RelPath resolve(RelPath other) {
    return RelPath.of(getPath().resolve(other.getPath()));
  }

  default RelPath subpath(int beginIndex, int endIndex) {
    return RelPath.of(getPath().subpath(beginIndex, endIndex));
  }
}
