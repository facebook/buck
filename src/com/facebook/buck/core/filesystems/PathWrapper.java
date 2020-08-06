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

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@link java.nio.file.Path} wrapper object, either absolute or relative.
 *
 * <p>Note this object is implemented directly for {@link BuckUnixPath}.
 */
public interface PathWrapper {
  Path getPath();

  @Override
  boolean equals(Object that);

  /**
   * This overload should not be used.
   *
   * <p>This code is incorrect: <code>
   * AbsPath p1 = ...
   * Path p2 = ...
   * p1.equals(p2);
   * </code> because {@code AbsPath} and {@link Path} are different "types" and should not be equal
   * according to {@link Object#equals(Object)} contract.
   *
   * <p>This overload helps to catch this error.
   */
  @Deprecated
  default boolean equals(Path that) {
    // mark parameter used for PMD
    Objects.hashCode(that);

    throw new AssertionError();
  }

  @Override
  int hashCode();

  @Override
  String toString();

  default boolean endsWith(String other) {
    return getPath().endsWith(other);
  }

  default boolean endsWith(Path path) {
    return getPath().endsWith(path);
  }

  default FileSystem getFileSystem() {
    return getPath().getFileSystem();
  }

  default boolean startsWith(Path other) {
    return getPath().startsWith(other);
  }
}
