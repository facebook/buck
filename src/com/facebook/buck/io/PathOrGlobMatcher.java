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

package com.facebook.buck.io;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Objects;

public class PathOrGlobMatcher {

  private static final Predicate<PathOrGlobMatcher> IS_PATH =
      new Predicate<PathOrGlobMatcher>() {
        @Override
        public boolean apply(PathOrGlobMatcher input) {
          return input.getType() == Type.PATH;
        }
      };

  private static final Function<PathOrGlobMatcher, Path> TO_PATH =
      new Function<PathOrGlobMatcher, Path>() {
        @Override
        public Path apply(PathOrGlobMatcher input) {
          return input.getPath();
        }
      };

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
    this.globPattern = Optional.absent();
    this.globMatcher = Optional.absent();
  }

  public PathOrGlobMatcher(Path root, String basePath) {
    this(root.getFileSystem().getPath(basePath));
  }

  public PathOrGlobMatcher(PathMatcher globMatcher, String globPattern) {
    this.type = Type.GLOB;
    this.basePath = Optional.absent();
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

    return Objects.equals(type, that.type) &&
        Objects.equals(basePath, that.basePath) &&
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
        "%s type=%s basePath=%s globPattern=%s",
        super.toString(),
        type,
        basePath,
        globPattern);
  }

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

  public static Predicate<PathOrGlobMatcher> isPath() {
    return IS_PATH;
  }

  public static Function<PathOrGlobMatcher, Path> toPath() {
    return TO_PATH;
  }

}
