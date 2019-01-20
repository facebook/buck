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

package com.facebook.buck.core.model;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Interface to allow looking up parents and children of build files. E.g. for a directory structure
 * that looks like: foo/BUCK foo/bar/baz/BUCK foo/bar/qux/BUCK
 *
 * <p>foo/BUCK is the parent of foo/bar/baz/BUCK and foo/bar/qux/BUCK.
 */
public interface BuildFileTree {
  /** @return paths relative to BuildTarget that contain their own build files. E.g. */
  Collection<Path> getChildPaths(BuildTarget target);

  /**
   * Returns the base path for a given path. The base path is the nearest directory at or above
   * filePath that contains a build file.
   *
   * @param filePath the path whose base path to find.
   * @return the base path if there is one.
   */
  Optional<Path> getBasePathOfAncestorTarget(Path filePath);
}
