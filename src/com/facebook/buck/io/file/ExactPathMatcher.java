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

package com.facebook.buck.io.file;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.InvalidPathException;
import java.util.Objects;
import java.util.Set;

/** Matcher that matches explicitly provided file paths. */
public class ExactPathMatcher implements PathMatcher {

  private final RelPath path;

  private ExactPathMatcher(RelPath relPath) {
    String path = relPath.toString();
    if (path.contains("*")) {
      throw new InvalidPathException(path, "Illegal char <*>", path.indexOf('*'));
    }
    this.path = relPath;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ExactPathMatcher)) {
      return false;
    }
    ExactPathMatcher that = (ExactPathMatcher) other;
    return Objects.equals(path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path);
  }

  @Override
  public String toString() {
    return String.format("%s path=%s", super.toString(), path);
  }

  @Override
  public boolean matches(RelPath path) {
    return this.path.equals(path);
  }

  @Override
  public String getGlob() {
    return path.toString();
  }

  public RelPath getPath() {
    return path;
  }

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    return ImmutableList.of(
        "match", getGlob(), "wholename", ImmutableMap.of("includedotfiles", true));
  }

  @Override
  public PathOrGlob getPathOrGlob() {
    return PathOrGlob.glob(getGlob());
  }

  /** @return The matcher for paths that are exactly the same as {@code path}. */
  public static ExactPathMatcher of(String path) {
    return of(RelPath.get(path));
  }

  /** @return The matcher for paths that are exactly the same as {@code path}. */
  public static ExactPathMatcher of(RelPath path) {
    return new ExactPathMatcher(path);
  }
}
