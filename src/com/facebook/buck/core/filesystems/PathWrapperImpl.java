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

/** Base implementation of two path wrappers. Not visible to users. */
abstract class PathWrapperImpl implements PathWrapper {
  protected final Path path;

  protected PathWrapperImpl(Path path) {
    this.path = path;
  }

  @Override
  public Path getPath() {
    return path;
  }

  @Override
  public String toString() {
    return path.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || this.getClass() != obj.getClass()) {
      return false;
    }
    return this.path.equals(((PathWrapperImpl) obj).path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }
}
