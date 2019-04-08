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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import java.nio.file.Path;

/** Generic factory to create {@link RawTargetNode} */
public interface RawTargetNodeFactory<T> {

  /**
   * Create new {@link RawTargetNode}
   *
   * @param cell {@Cell} object that current build target belongs to
   * @param buildFile A path to a build file that has the corresponding build target; can be either
   *     absolute or relative, only used for displaying errors
   * @param buildTarget A build target that uniquely identifies created {@link RawTargetNode}
   * @param rawNode Raw attributes that forms the node, usually a Map where a key is attribute name
   *     as string and value is attribute value as object.
   */
  RawTargetNode create(
      Cell cell, Path buildFile, UnconfiguredBuildTargetView buildTarget, T rawNode);
}
