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

import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRule;

/**
 * An Immutable Key to a {@link BuildRule} for computation in {@link GraphTransformationEngine}. The
 * Key is used to represent what {@link BuildRule} subgraph we are attempting to compute.
 *
 * <p>The {@link BuildRule} subgraph is identified by:
 *
 * <ul>
 *   <li>the {@link TargetNode} corresponding to the {@link BuildTarget} which contains information
 *       about the desired {@link BuildTarget}, including flavour information, and cell path, etc.
 * </ul>
 */
public interface BuildRuleKey {

  BuildTarget getBuildTarget();

  TargetNode<?> getTargetNode();

  BuildRuleCreationContextWithTargetGraph getBuildRuleCreationContext();
}
