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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.parser.api.RawTargetNode;

/** Generic factory to create {@link UnconfiguredTargetNode} */
public interface UnconfiguredTargetNodeFactory {

  /**
   * Create new {@link UnconfiguredTargetNode}
   *
   * @param cell {@Cell} object that current build target belongs to
   * @param buildFile An absolute path to a build file that has the corresponding build target
   * @param buildTarget A build target that uniquely identifies created {@link
   *     UnconfiguredTargetNode}
   * @param dependencyStack
   * @param rawNode Raw attributes that forms the node, a Map where a key is attribute name as
   * @param pkg Package to apply to this node.
   */
  UnconfiguredTargetNode create(
      Cell cell,
      AbsPath buildFile,
      UnconfiguredBuildTarget buildTarget,
      DependencyStack dependencyStack,
      RawTargetNode rawNode,
      Package pkg);
}
