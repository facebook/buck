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

package com.facebook.buck.query;

import com.facebook.buck.graph.ParentTraversableGraph;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSet;

/**
 * A version of the TargetGraph which allows parent traversal.
 *
 * This should only ever be used from query-like commands (buck query, buck targets, ...), and
 * never buck build. Parent traversal is in general unsafe on a TargetGraph, but in limited
 * readonly environments like query it is required for efficiency.
 */
public class QueryTargetGraph extends TargetGraph
    implements ParentTraversableGraph<TargetNode<?, ?>> {

  public QueryTargetGraph(TargetGraph graph) {
    super(graph);
  }

  @Override
  public ImmutableSet<TargetNode<?, ?>> getIncomingNodesFor(TargetNode<?, ?> sink) {
    return delegate.getIncomingNodesFor(sink);
  }
}
