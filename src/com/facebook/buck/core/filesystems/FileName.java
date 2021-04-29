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

/** Wrapper for string. */
public class FileName {
  private final String name;

  private FileName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  /**
   * Intern name same way for both this class and {@link
   * com.facebook.buck.core.path.ForwardRelativePath}.
   */
  public static String internName(String name) {
    return name.intern();
  }

  /**
   * Create a file name.
   *
   * @throws IllegalArgumentException if given name is not a valid file name.
   */
  public static FileName of(String name) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException("file name must not be empty");
    }
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(
          String.format("file name must not contain slashes: '%s'", name));
    }
    if (name.equals(".") || name.equals("..")) {
      throw new IllegalArgumentException(
          String.format("file name must not be dot or dot-dot: '%s'", name));
    }
    return new FileName(internName(name));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FileName fileName = (FileName) o;
    // Identity comparison is safe because strings are interned.
    return name == fileName.name;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public String toString() {
    return name;
  }
}
