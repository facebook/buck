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
import java.nio.file.Path;
import java.util.Set;

/** A contract for matching {@link Path}s. */
public interface PathMatcher extends java.nio.file.PathMatcher {

  /**
   * Transforms this matcher into a Watchman match query arguments matching the same set of paths as
   * this matcher.
   */
  ImmutableList<?> toWatchmanMatchQuery(Path projectRoot, Set<Capability> capabilities);
}
