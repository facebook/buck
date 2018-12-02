/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Set;

/** Matcher that matches explicitly provided file paths. */
public class ExactPathMatcher implements PathMatcher {

  private final Path path;

  private ExactPathMatcher(Path path) {
    this.path = path;
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
  public boolean matches(Path path) {
    return this.path.equals(path);
  }

  private String getGlob() {
    return path.toString();
  }

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    String pathGlob = getGlob();
    return ImmutableList.of(
        "match", pathGlob, "wholename", ImmutableMap.of("includedotfiles", true));
  }

  @Override
  public String getPathOrGlob() {
    return getGlob();
  }

  /** @return The matcher for paths that are exactly the same as {@code path}. */
  public static ExactPathMatcher of(String path) {
    return new ExactPathMatcher(Paths.get(path));
  }
}
