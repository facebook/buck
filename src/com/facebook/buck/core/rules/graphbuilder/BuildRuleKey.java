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

package com.facebook.buck.core.rules.graphbuilder;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.TargetNode;
import org.immutables.value.Value;

/**
 * An Immutable Key to a {@link BuildRule} for computation in {@link
 * com.facebook.buck.graph.transformationengine.AsyncTransformationEngine}. The Key is used to
 * represent what {@link com.facebook.buck.rules.BuildRule} subgraph we are attempting to compute.
 *
 * <p>The {@link com.facebook.buck.rules.BuildRule} subgraph is identified by:
 *
 * <ul>
 *   <li>the {@link com.facebook.buck.rules.TargetNode} corresponding to the {@link BuildTarget}
 *       which contains information about the desired {@link BuildTarget}, including flavour
 *       information, and cell path, etc.
 * </ul>
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class BuildRuleKey {

  @Value.Parameter
  @Value.Auxiliary
  public abstract BuildTarget getBuildTarget();

  @Value.Derived
  protected TargetNodeWrapper getTargetNodeWrapper() {
    return TargetNodeWrapper.of(
        getBuildRuleCreationContext().getTargetGraph().get(getBuildTarget()));
  }

  public TargetNode<?, ?> getTargetNode() {
    return getTargetNodeWrapper().getTargetNode();
  }

  @Value.Parameter
  @Value.Auxiliary
  public abstract BuildRuleCreationContext getBuildRuleCreationContext();
}
