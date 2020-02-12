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

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Absolute path. */
public interface AbsPath extends PathWrapper {
  /**
   * Cosnstruct using {@link java.nio.file.Path} object.
   *
   * <p>Note this operation is just a cast if the path is {@link BuckUnixPath}.
   *
   * @throws RuntimeException the path is not absolute.
   */
  static AbsPath of(Path path) {
    if (path instanceof AbsPath) {
      return (AbsPath) path;
    } else {
      return new AbsPathImpl(path);
    }
  }

  static AbsPath get(String path) {
    return AbsPath.of(Paths.get(path));
  }

  /** Behaves exactly like {@link Path#normalize()}. */
  default AbsPath normalize() {
    return of(getPath().normalize());
  }

  default AbsPath toRealPath(LinkOption... options) throws IOException {
    return AbsPath.of(getPath().toRealPath(options));
  }

  default AbsPath resolve(Path path) {
    return AbsPath.of(getPath().resolve(path));
  }

  default AbsPath resolve(RelPath other) {
    return resolve(other.getPath());
  }
}
