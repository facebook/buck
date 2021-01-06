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

package com.facebook.buck.parser.spec;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser;
import com.google.common.collect.ImmutableMap;

/** A specification used by the parser to match {@link TargetNode} objects. */
public interface TargetNodeSpec {

  enum TargetType {
    SINGLE_TARGET,
    MULTIPLE_TARGETS
  }

  /**
   * @return whether spec is a single target requested by a name or a list of targets requested with
   *     recursive spec (i.e. ... )
   */
  TargetType getTargetType();

  /** @return the targets which should be built according to this spec */
  ImmutableMap<BuildTarget, TargetNodeMaybeIncompatible> filter(
      Iterable<TargetNodeMaybeIncompatible> nodes);

  /**
   * @return a {@link BuildFileSpec} representing the build files to parse to search for specific
   *     build target.
   */
  BuildFileSpec getBuildFileSpec();

  /**
   * Convert from a legacy {@link TargetNodeSpec} to a new-hotness {@link BuildTargetPattern}.
   *
   * <p>This conversion is imperfect and best-effort. If possible, use {@link
   * BuildTargetPatternParser#parse(String, CellNameResolver)} to create a {@link
   * BuildTargetPattern} instead.
   *
   * <p>This conversion is lossy. Some attributes, such as whether test targets should be included,
   * are not reflected in the result.
   *
   * @param cell this {@link TargetNodeSpec}'s cell. Some implementations of {@link TargetNodeSpec}
   *     do not store a cell name, so {@code cell} provides the name.
   * @return a pattern matching the same targets as this pattern.
   * @throws IllegalArgumentException {@code cell} refers to a cell different from this {@link
   *     TargetNodeSpec}'s cell. This exception is best-effort.
   */
  BuildTargetPattern getBuildTargetPattern(Cell cell);
}
