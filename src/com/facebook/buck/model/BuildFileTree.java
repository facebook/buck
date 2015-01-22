/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;

import java.nio.file.Path;
import java.util.Collection;

/**
 * Interface to allow looking up parents and children of build files.
 * E.g. for a directory structure that looks like:
 *   foo/BUCK
 *   foo/bar/baz/BUCK
 *   foo/bar/qux/BUCK
 *
 * foo/BUCK is the parent of foo/bar/baz/BUCK and foo/bar/qux/BUCK.
 */
public abstract class BuildFileTree {
  /**
   * No-arg constructor, provided so that subclasses can be constructed.
   */
  protected BuildFileTree() {}

  /**
   * @return paths relative to BuildTarget that contain their own build files. E.g.
   */
  public abstract Collection<Path> getChildPaths(BuildTarget target);

  /**
   * Returns the base path for a given path. The base path is the nearest directory at or
   * above filePath that contains a build file.
   *
   * @param filePath the path whose base path to find.
   * @return the base path if there is one.
   */
  public abstract Optional<Path> getBasePathOfAncestorTarget(Path filePath);

  /**
   * Returns the base paths for zero or more targets.
   *
   * @param targets targets to return base paths for
   * @return base paths for targets
   */
  protected static Collection<Path> collectBasePaths(Iterable<? extends BuildTarget> targets) {

    return FluentIterable.from(targets).transform(
        new Function<BuildTarget, Path>() {
          @Override
          public Path apply(BuildTarget input) {
            return input.getBasePath();
          }
        })
        .toSet();
  }
}
