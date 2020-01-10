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

package com.facebook.buck.core.model;

import com.facebook.buck.core.path.ForwardRelativePath;
import com.google.common.base.Preconditions;

/** {@code //foo/bar} part of {@code cell//foo/bar:baz}. */
public class BaseName implements Comparable<BaseName> {
  public static final BaseName ROOT = new BaseName(ForwardRelativePath.EMPTY);

  private final ForwardRelativePath path;

  private BaseName(ForwardRelativePath path) {
    this.path = path;
  }

  /** Parse a string as a base name (string must start with "//"). */
  public static BaseName of(String baseName) {
    Preconditions.checkArgument(
        baseName.startsWith("//"), "base name must start with slash-slash: %s", baseName);
    if (baseName.length() == "//".length()) {
      return ROOT;
    } else {
      return ofPath(ForwardRelativePath.ofSubstring(baseName, "//".length()));
    }
  }

  /** Create a base name from path (prepend "//"). */
  public static BaseName ofPath(ForwardRelativePath path) {
    if (path.isEmpty()) {
      return ROOT;
    } else {
      return new BaseName(path);
    }
  }

  public ForwardRelativePath getPath() {
    return path;
  }

  @Override
  public String toString() {
    return "//" + path;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return this.path.equals(((BaseName) o).path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @Override
  public int compareTo(BaseName o) {
    return this.path.compareTo(o.path);
  }
}
