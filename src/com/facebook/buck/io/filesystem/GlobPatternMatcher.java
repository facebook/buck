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
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Matcher that matches paths described by the glob pattern. */
public class GlobPatternMatcher implements PathMatcher {

  private final String globPattern;
  private final java.nio.file.PathMatcher globPatternMatcher;

  private GlobPatternMatcher(String gloPattern, java.nio.file.PathMatcher globPatternMatcher) {
    this.globPattern = gloPattern;
    this.globPatternMatcher = globPatternMatcher;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof GlobPatternMatcher)) {
      return false;
    }
    GlobPatternMatcher that = (GlobPatternMatcher) other;
    // We don't compare globPatternMatcher here, since
    // sun.nio.fs.UnixFileSystem.getPathMatcher()
    // returns an anonymous class which doesn't implement equals().
    return Objects.equals(globPattern, that.globPattern);
  }

  @Override
  public int hashCode() {
    return Objects.hash(globPattern);
  }

  @Override
  public String toString() {
    return String.format("%s globPattern=%s", super.toString(), globPattern);
  }

  @Override
  public boolean matches(Path path) {
    return globPatternMatcher.matches(path);
  }

  private String getGlob() {
    return globPattern;
  }

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    String ignoreGlob = getGlob();
    return ImmutableList.of(
        "match", ignoreGlob, "wholename", ImmutableMap.of("includedotfiles", true));
  }

  @Override
  public String getPathOrGlob() {
    return getGlob();
  }

  /** @return The matcher for paths that start with {@code basePath}. */
  public static GlobPatternMatcher of(String globPattern) {
    return new GlobPatternMatcher(
        globPattern, FileSystems.getDefault().getPathMatcher("glob:" + globPattern));
  }
}
