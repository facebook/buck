/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.parser.targetnode;

import com.facebook.buck.core.graph.transformation.model.ClassBasedComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputationIdentifier;
import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNodeWithDeps;
import java.nio.file.Path;
import org.immutables.value.Value;

/**
 * Transformation key containing {@link RawTargetNode} to translate it to {@link
 * RawTargetNodeWithDeps}.
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class RawTargetNodeToRawTargetNodeWithDepsKey
    implements ComputeKey<RawTargetNodeWithDeps> {

  public static final ComputationIdentifier<RawTargetNodeWithDeps> IDENTIFIER =
      ClassBasedComputationIdentifier.of(
          RawTargetNodeToRawTargetNodeWithDepsKey.class, RawTargetNodeWithDeps.class);

  /** {@link RawTargetNode} which should be translated to {@link RawTargetNodeWithDeps} */
  @Value.Parameter
  public abstract RawTargetNode getRawTargetNode();

  /**
   * {@link Path} to the root of a package that has this {@link RawTargetNode}, relative to parse
   * root, usually cell root
   */
  @Value.Parameter
  public abstract Path getPackagePath();

  @Override
  public ComputationIdentifier<RawTargetNodeWithDeps> getIdentifier() {
    return IDENTIFIER;
  }
}
