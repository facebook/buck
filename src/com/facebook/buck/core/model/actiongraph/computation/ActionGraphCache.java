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
package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import javax.annotation.Nullable;

/** Contains cached action graphs. */
public class ActionGraphCache {

  private final Cache<TargetGraph, ActionGraphAndBuilder> previousActionGraphs;
  private final IncrementalActionGraphGenerator incrementalActionGraphGenerator;

  public ActionGraphCache(int maxEntries) {
    previousActionGraphs = CacheBuilder.newBuilder().maximumSize(maxEntries).build();
    incrementalActionGraphGenerator = new IncrementalActionGraphGenerator();
  }

  public void invalidateCache() {
    previousActionGraphs.invalidateAll();
  }

  @Nullable
  public ActionGraphAndBuilder getIfPresent(TargetGraph targetGraph) {
    return previousActionGraphs.getIfPresent(targetGraph);
  }

  public boolean isEmpty() {
    return previousActionGraphs.size() == 0;
  }

  public long size() {
    return previousActionGraphs.size();
  }

  public void put(TargetGraph targetGraph, ActionGraphAndBuilder actionGraphAndBuilder) {
    previousActionGraphs.put(targetGraph, actionGraphAndBuilder);
  }

  public void populateActionGraphBuilderWithCachedRules(
      BuckEventBus eventBus, TargetGraph targetGraph, ActionGraphBuilder graphBuilder) {
    incrementalActionGraphGenerator.populateActionGraphBuilderWithCachedRules(
        eventBus, targetGraph, graphBuilder);
  }
}
