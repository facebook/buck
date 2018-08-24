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

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory.ActionGraphCreationLifecycleListener;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.MultiThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.AbstractBottomUpTraversal;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

public class ParallelActionGraphFactory implements ActionGraphFactoryDelegate {
  private static final Logger LOG = Logger.get(ParallelActionGraphFactory.class);

  private final CloseableMemoizedSupplier<ForkJoinPool> poolSupplier;
  private final CellProvider cellProvider;

  public ParallelActionGraphFactory(
      CloseableMemoizedSupplier<ForkJoinPool> poolSupplier, CellProvider cellProvider) {
    this.poolSupplier = poolSupplier;
    this.cellProvider = cellProvider;
  }

  @Override
  public ActionGraphAndBuilder create(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      ActionGraphCreationLifecycleListener actionGraphCreationLifecycleListener) {
    ForkJoinPool pool = poolSupplier.get();
    ActionGraphBuilder graphBuilder =
        new MultiThreadedActionGraphBuilder(pool, targetGraph, transformer, cellProvider);
    HashMap<BuildTarget, CompletableFuture<BuildRule>> futures = new HashMap<>();

    actionGraphCreationLifecycleListener.onCreate(graphBuilder);

    LOG.debug("start target graph walk");
    new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(targetGraph) {
      @Override
      public void visit(TargetNode<?> node) {
        // If we're loading this node from cache, we don't need to wait on our children, as the
        // entire subgraph will be loaded from cache.
        CompletableFuture<BuildRule>[] depFutures =
            targetGraph
                .getOutgoingNodesFor(node)
                .stream()
                .map(dep -> Preconditions.checkNotNull(futures.get(dep.getBuildTarget())))
                .<CompletableFuture<BuildRule>>toArray(CompletableFuture[]::new);
        futures.put(
            node.getBuildTarget(),
            CompletableFuture.allOf(depFutures)
                .thenApplyAsync(ignored -> graphBuilder.requireRule(node.getBuildTarget()), pool));
      }
    }.traverse();

    // Wait for completion. The results are ignored as we only care about the rules populated in
    // the graphBuilder, which is a superset of the rules generated directly from target nodes.
    try {
      CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[futures.size()]))
          .join();
    } catch (CompletionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new IllegalStateException("unexpected checked exception", e);
    }
    LOG.debug("end target graph walk");

    return ActionGraphAndBuilder.builder()
        .setActionGraph(new ActionGraph(graphBuilder.getBuildRules()))
        .setActionGraphBuilder(graphBuilder)
        .build();
  }
}
