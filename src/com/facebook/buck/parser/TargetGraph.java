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

package com.facebook.buck.parser;

import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.DefaultImmutableDirectedAcyclicGraph;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RepositoryFactory;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;

/**
 * Represents the graph of {@link com.facebook.buck.rules.TargetNode}s constructed
 * by parsing the build files.
 */
public class TargetGraph extends DefaultImmutableDirectedAcyclicGraph<TargetNode<?>> {

  private final RepositoryFactory repositoryFactory;

  public TargetGraph(
      MutableDirectedGraph<TargetNode<?>> graph,
      RepositoryFactory repositoryFactory) {
    super(graph);
    this.repositoryFactory = Preconditions.checkNotNull(repositoryFactory);
  }

  public ActionGraph buildActionGraph() {
    final BuildRuleResolver ruleResolver = new BuildRuleResolver();
    final MutableDirectedGraph<BuildRule> actionGraph = new MutableDirectedGraph<>();

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(this) {

          @Override
          public void visit(TargetNode<?> node) {
            TargetNodeToBuildRuleTransformer<?> transformer =
                new TargetNodeToBuildRuleTransformer<>(node);
            BuildRule rule;
            try {
              Repository targetRepo = repositoryFactory.getRepositoryByCanonicalName(
                  node.getBuildTarget().getRepository());
              rule = transformer.transform(ruleResolver, targetRepo.getBuildTargetParser());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            } catch (IOException | InterruptedException e) {
              throw new HumanReadableException(e.getMessage());
            }
            ruleResolver.addToIndex(rule.getBuildTarget(), rule);
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
            new AbstractDependencyVisitor(rule) {
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

                return depsToVisit == null ? ImmutableSet.<BuildRule>of() : depsToVisit.build();
              }
            }.start();
          }
        };

    bottomUpTraversal.traverse();
    return bottomUpTraversal.getResult();
  }
}
