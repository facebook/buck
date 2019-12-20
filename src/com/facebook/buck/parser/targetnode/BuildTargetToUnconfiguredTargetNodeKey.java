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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Transformation key containing build target to get {@link UnconfiguredTargetNode} for. */
@BuckStylePrehashedValue
public abstract class BuildTargetToUnconfiguredTargetNodeKey
    implements ComputeKey<UnconfiguredTargetNode> {

  public static final ComputationIdentifier<UnconfiguredTargetNode> IDENTIFIER =
      ClassBasedComputationIdentifier.of(
          BuildTargetToUnconfiguredTargetNodeKey.class, UnconfiguredTargetNode.class);

  /** Build target that uniquely identifies {@link UnconfiguredTargetNode} */
  public abstract UnconfiguredBuildTarget getBuildTarget();

  /**
   * {@link Path} to the root of a package that has this {@link UnconfiguredTargetNode}, relative to
   * parse root, usually cell root
   */
  public abstract Path getPackagePath();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(!getPackagePath().isAbsolute());
  }

  @Override
  public ComputationIdentifier<UnconfiguredTargetNode> getIdentifier() {
    return IDENTIFIER;
  }
}
