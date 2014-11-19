/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.DefaultImmutableDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;

/**
 * Represents the graph of {@link com.facebook.buck.rules.TargetNode}s constructed
 * by parsing the build files.
 */
public class TargetGraph extends DefaultImmutableDirectedAcyclicGraph<TargetNode<?>> {

  public static final TargetGraph EMPTY = new TargetGraph(
      new MutableDirectedGraph<TargetNode<?>>(),
      Optional.<BuckEventBus>absent());

  private final ImmutableMap<BuildTarget, TargetNode<?>> targetsToNodes;
  private final Supplier<ActionGraph> actionGraphSupplier;
  private final Optional<BuckEventBus> buckEventBus;

  public TargetGraph(
      MutableDirectedGraph<TargetNode<?>> graph,
      Optional<BuckEventBus> buckEventBus) {
    super(graph);
    ImmutableMap.Builder<BuildTarget, TargetNode<?>> builder = ImmutableMap.builder();
    for (TargetNode<?> node : graph.getNodes()) {
      builder.put(node.getBuildTarget(), node);
    }
    this.targetsToNodes = builder.build();
    actionGraphSupplier = createActionGraphSupplier();
    this.buckEventBus = buckEventBus;
  }

  @Nullable
  public TargetNode<?> get(BuildTarget target) {
    return targetsToNodes.get(target);
  }

  public Iterable<TargetNode<?>> getAll(Iterable<BuildTarget> targets) {
    return Iterables.transform(
        targets,
        new Function<BuildTarget, TargetNode<?>>() {
          @Override
          public TargetNode<?> apply(BuildTarget input) {
            return Preconditions.checkNotNull(get(input));
          }
        });
  }

  private Supplier<ActionGraph> createActionGraphSupplier() {
    final TargetGraph targetGraph = this;
    return Suppliers.memoize(
        new Supplier<ActionGraph>() {
          @Override
          public ActionGraph get() {
            if (buckEventBus.isPresent()) {
              buckEventBus.get().post(ActionGraphEvent.started());
            }

            final BuildRuleResolver ruleResolver = new BuildRuleResolver();
            final MutableDirectedGraph<BuildRule> actionGraph = new MutableDirectedGraph<>();

            final TargetNodeToBuildRuleTransformer transformer =
                new TargetNodeToBuildRuleTransformer();

            AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
                new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

                  @Override
                  public void visit(TargetNode<?> node) {
                    BuildRule rule;
                    try {
                      rule = transformer.transform(TargetGraph.this, ruleResolver, node);
                    } catch (NoSuchBuildTargetException e) {
                      throw new HumanReadableException(e);
                    }
                    ruleResolver.addToIndex(rule);
                    actionGraph.addNode(rule);

                    for (BuildRule buildRule : rule.getDeps()) {
                      if (buildRule.getBuildTarget().isFlavored()) {
                        addGraphEnhancedDeps(rule);
                      }
                    }

                    for (BuildRule dep : rule.getDeps()) {
                      actionGraph.addEdge(rule, dep);
                    }

                  }

                  @Override
                  public ActionGraph getResult() {
                    return new ActionGraph(actionGraph);
                  }

                  private void addGraphEnhancedDeps(BuildRule rule) {
                    new AbstractBreadthFirstTraversal<BuildRule>(rule) {
                      @Override
                      public ImmutableSet<BuildRule> visit(BuildRule rule) {
                        ImmutableSet.Builder<BuildRule> depsToVisit = null;
                        boolean isRuleFlavored = rule.getBuildTarget().isFlavored();

                        for (BuildRule dep : rule.getDeps()) {
                          boolean isDepFlavored = dep.getBuildTarget().isFlavored();
                          if (isRuleFlavored || isDepFlavored) {
                            actionGraph.addEdge(rule, dep);
                          }

                          if (isDepFlavored) {
                            if (depsToVisit == null) {
                              depsToVisit = ImmutableSet.builder();
                            }
                            depsToVisit.add(dep);
                          }
                        }

                        return depsToVisit == null ?
                            ImmutableSet.<BuildRule>of() :
                            depsToVisit.build();
                      }
                    }.start();
                  }
                };
            bottomUpTraversal.traverse();
            ActionGraph result = bottomUpTraversal.getResult();
            if (buckEventBus.isPresent()) {
              buckEventBus.get().post(ActionGraphEvent.finished());
            }
            return result;
          }
        });
  }

  public ActionGraph getActionGraph() {
    return actionGraphSupplier.get();
  }
}
