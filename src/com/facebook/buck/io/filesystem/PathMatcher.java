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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Set;
import org.immutables.value.Value;

/** A contract for matching {@link Path}s. */
public interface PathMatcher extends java.nio.file.PathMatcher {

  /**
   * Transforms this matcher into a Watchman match query arguments matching the same set of paths as
   * this matcher.
   */
  ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities);

  /**
   * Returns {@link PathOrGlob} object that contains a value that represents a path or glob pattern
   * identifying paths that should be matched by this matcher, and a way to figure out whether the
   * value is a Path or Glob.
   */
  PathOrGlob getPathOrGlob();

  /** Returns a glob pattern identifying paths that should be matched by this matcher. */
  String getGlob();

  /**
   * Wrapper around type of the path matcher's {@link #getPathOrGlob} return value (Path or Glob
   * type and a value).
   */
  @BuckStyleValue
  abstract class PathOrGlob {

    /** Type of the path matcher's {@link #getPathOrGlob} return value. Path or Glob */
    protected enum Type {
      PATH,
      GLOB
    }

    public abstract String getValue();

    protected abstract Type getType();

    @Value.Derived
    public boolean isPath() {
      return getType() == Type.PATH;
    }

    @Value.Derived
    public boolean isGlob() {
      return getType() == Type.GLOB;
    }

    public static PathOrGlob glob(String glob) {
      return ImmutablePathOrGlob.ofImpl(glob, Type.GLOB);
    }

    public static PathOrGlob path(RelPath path) {
      return ImmutablePathOrGlob.ofImpl(path.toString(), Type.PATH);
    }
  }
}
