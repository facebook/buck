/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public class PartialGraphFactory {

  private PartialGraphFactory() {}

  /**
   * Creates a PartialGraph via dependency injection.
   * <p>
   * Normally, a PartialGraph is created via factory methods that ensure the integrity of the
   * PartialGraph by construction. This factory is used for tests, so it sidesteps the integrity
   * checks. Therefore, it is the responsibility of the caller to ensure that the graph is a
   * DependencyGraph built from the list of targets.
   */
  public static PartialGraph newInstance(ActionGraph graph, ImmutableSet<BuildTarget> targets) {
    Preconditions.checkNotNull(graph);
    Preconditions.checkNotNull(targets);
    return new PartialGraph(graph, targets);
  }
}
