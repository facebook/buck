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

package com.facebook.buck.rust;

import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * Get details about a linkable thing - either the actual linkable file, and the set
 * of directories with its dependencies.
 */
interface RustLinkable {
  /**
   * Return the crate name for this linkable (ie, the name used in an "extern crate X;" line,
   * and in an --extern option to rustc).
   *
   * @return Crate name
   */
  String getLinkTarget();

  /**
   * Return full path to the rlib, used as a direct dependency (ie, passed to rustc as
   * "--extern [crate_name]=path/to/libcrate_name.rlib"
   *
   * @return path to rlib file
   */
  Path getLinkPath();

  /**
   * Return the set of paths to find the transitive dependencies for this linkable thing,
   * passed to rustc as "-L dependency=path/to/dependency".
   *
   * @return set of paths
   */
  ImmutableSortedSet<Path> getDependencyPaths();
}
