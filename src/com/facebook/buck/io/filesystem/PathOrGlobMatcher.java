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

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.watchman.Capability;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class PathOrGlobMatcher implements com.facebook.buck.io.filesystem.PathMatcher {

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Path projectRoot, Set<Capability> capabilities) {
    switch (getType()) {
      case PATH:
        Path ignorePath = getPath();
        if (ignorePath.isAbsolute()) {
          ignorePath = MorePaths.relativize(projectRoot, ignorePath);
        }
        if (capabilities.contains(Capability.DIRNAME)) {
          return ImmutableList.of("dirname", ignorePath.toString());
        } else {
          return ImmutableList.of("match", ignorePath + File.separator + "*", "wholename");
        }
      case GLOB:
        String ignoreGlob = getGlob();
        return ImmutableList.of(
            "match", ignoreGlob, "wholename", ImmutableMap.of("includedotfiles", true));
      default:
        throw new RuntimeException(String.format("Unsupported type: '%s'", getType()));
    }
  }

  public enum Type {
    PATH,
    GLOB
  }

  private final Type type;
  private final Optional<Path> basePath;
  private final Optional<PathMatcher> globMatcher;
  private final Optional<String> globPattern;

  public PathOrGlobMatcher(Path basePath) {
    this.type = Type.PATH;
    this.basePath = Optional.of(basePath);
    this.globPattern = Optional.empty();
    this.globMatcher = Optional.empty();
  }

  public PathOrGlobMatcher(Path root, String basePath) {
    this(root.getFileSystem().getPath(basePath));
  }

  public PathOrGlobMatcher(PathMatcher globMatcher, String globPattern) {
    this.type = Type.GLOB;
    this.basePath = Optional.empty();
    this.globMatcher = Optional.of(globMatcher);
    this.globPattern = Optional.of(globPattern);
  }

  public PathOrGlobMatcher(String globPattern) {
    this(FileSystems.getDefault().getPathMatcher("glob:" + globPattern), globPattern);
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
        && Objects.equals(basePath, that.basePath)
        &&
        // We don't compare globMatcher here, since sun.nio.fs.UnixFileSystem.getPathMatcher()
        // returns an anonymous class which doesn't implement equals().
        Objects.equals(globPattern, that.globPattern);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, basePath, globPattern);
  }

  @Override
  public String toString() {
    return String.format(
        "%s type=%s basePath=%s globPattern=%s", super.toString(), type, basePath, globPattern);
  }

  @Override
  public boolean matches(Path path) {
    switch (type) {
      case PATH:
        return path.startsWith(basePath.get());
      case GLOB:
        return globMatcher.get().matches(path);
    }
    throw new RuntimeException("Unsupported type " + type);
  }

  public Path getPath() {
    Preconditions.checkState(type == Type.PATH);
    return basePath.get();
  }

  public String getGlob() {
    Preconditions.checkState(type == Type.GLOB);
    return globPattern.get();
  }

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
