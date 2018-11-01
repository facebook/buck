/*
 * Copyright 2016-present Facebook, Inc.
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class PathOrGlobMatcher implements com.facebook.buck.io.filesystem.PathMatcher {

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    switch (getType()) {
      case PATH:
        return pathPrefixMatcher.get().toWatchmanMatchQuery(capabilities);
      case GLOB:
        return globPatternMatcher.get().toWatchmanMatchQuery(capabilities);
      default:
        throw new RuntimeException(String.format("Unsupported type: '%s'", getType()));
    }
  }

  public enum Type {
    PATH,
    GLOB
  }

  private final Type type;
  private final Optional<RecursiveFileMatcher> pathPrefixMatcher;
  private final Optional<GlobPatternMatcher> globPatternMatcher;

  public PathOrGlobMatcher(Path basePath) {
    this.type = Type.PATH;
    this.pathPrefixMatcher = Optional.of(RecursiveFileMatcher.of(basePath));
    this.globPatternMatcher = Optional.empty();
  }

  public PathOrGlobMatcher(String globPattern) {
    this.type = Type.GLOB;
    this.pathPrefixMatcher = Optional.empty();
    this.globPatternMatcher = Optional.of(GlobPatternMatcher.of(globPattern));
  }

  public Type getType() {
    return type;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof PathOrGlobMatcher)) {
      return false;
    }

    PathOrGlobMatcher that = (PathOrGlobMatcher) other;

    return Objects.equals(type, that.type)
        && Objects.equals(pathPrefixMatcher, that.pathPrefixMatcher)
        && Objects.equals(globPatternMatcher, that.globPatternMatcher);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, pathPrefixMatcher, globPatternMatcher);
  }

  @Override
  public String toString() {
    return String.format(
        "%s type=%s pathPrefixMatcher=%s globPattern=%s",
        super.toString(), type, pathPrefixMatcher, globPatternMatcher);
  }

  @Override
  public boolean matches(Path path) {
    switch (type) {
      case PATH:
        return pathPrefixMatcher.get().matches(path);
      case GLOB:
        return globPatternMatcher.get().matches(path);
    }
    throw new RuntimeException("Unsupported type " + type);
  }

  public Path getPath() {
    Preconditions.checkState(type == Type.PATH);
    return pathPrefixMatcher.get().getPath();
  }

  private String getGlob() {
    Preconditions.checkState(type == Type.GLOB);
    return globPatternMatcher.get().getGlob();
  }

  @Override
  public String getPathOrGlob() {
    switch (type) {
      case PATH:
        return getPath().toString();
      case GLOB:
        return getGlob();
    }
    throw new RuntimeException(String.format("Unsupported type: '%s'", type));
  }
}
