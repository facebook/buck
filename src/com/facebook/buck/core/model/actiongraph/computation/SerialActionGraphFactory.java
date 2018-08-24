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
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory.ActionGraphCreationLifecycleListener;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.util.graph.AbstractBottomUpTraversal;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ActionGraphPerfStatEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.Iterables;
import java.util.stream.StreamSupport;

public class SerialActionGraphFactory implements ActionGraphFactoryDelegate {
  private static final Logger LOG = Logger.get(SerialActionGraphFactory.class);

  private final BuckEventBus eventBus;
  private final CellProvider cellProvider;
  private final boolean shouldInstrumentGraphBuilding;

  public SerialActionGraphFactory(
      BuckEventBus eventBus, CellProvider cellProvider, boolean shouldInstrumentGraphBuilding) {
    this.eventBus = eventBus;
    this.cellProvider = cellProvider;
    this.shouldInstrumentGraphBuilding = shouldInstrumentGraphBuilding;
  }

  @Override
  public ActionGraphAndBuilder create(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      ActionGraphCreationLifecycleListener actionGraphCreationLifecycleListener) {
    // TODO: Reduce duplication between the serial and parallel creation methods.
    ActionGraphBuilder graphBuilder =
        new SingleThreadedActionGraphBuilder(targetGraph, transformer, cellProvider);

    actionGraphCreationLifecycleListener.onCreate(graphBuilder);

    LOG.debug("start target graph walk");
    new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(targetGraph) {
      @Override
      public void visit(TargetNode<?> node) {
        if (shouldInstrumentGraphBuilding) {
          Clock clock = new DefaultClock();
          try (Scope ignored =
              ActionGraphPerfStatEvent.start(
                  clock,
                  eventBus,
                  () -> Iterables.size(graphBuilder.getBuildRules()),
                  () ->
                      StreamSupport.stream(graphBuilder.getBuildRules().spliterator(), true)
                          .filter(
                              rule ->
                                  rule instanceof NoopBuildRule
                                      || rule instanceof NoopBuildRuleWithDeclaredAndExtraDeps)
                          .count(),
                  node.getDescription().getClass().getName(),
                  node.getBuildTarget().getFullyQualifiedName())) {
            graphBuilder.requireRule(node.getBuildTarget());
          }
        } else {
          graphBuilder.requireRule(node.getBuildTarget());
        }
      }
    }.traverse();
    LOG.debug("end target graph walk");

    return ActionGraphAndBuilder.builder()
        .setActionGraph(new ActionGraph(graphBuilder.getBuildRules()))
        .setActionGraphBuilder(graphBuilder)
        .build();
  }
}
