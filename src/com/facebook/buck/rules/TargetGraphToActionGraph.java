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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.HumanReadableException;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

public class TargetGraphToActionGraph implements TargetGraphTransformer {

  private static final Logger LOG = Logger.get(TargetGraphToActionGraph.class);

  private final BuckEventBus eventBus;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;

  private volatile int hashOfTargetGraph;
  @Nullable
  private volatile Pair<ActionGraph, BuildRuleResolver> result;


  public TargetGraphToActionGraph(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    this.eventBus = eventBus;
    this.buildRuleGenerator = buildRuleGenerator;
  }

  @Override
  public synchronized Pair<ActionGraph, BuildRuleResolver> apply(TargetGraph targetGraph) {
    if (result != null) {
      if (targetGraph.hashCode() == hashOfTargetGraph) {
        return result;
      }
      LOG.info("Flushing cached action graph. May be a performance hit.");
    }

    result = createActionGraph(targetGraph);
    hashOfTargetGraph = targetGraph.hashCode();

    return result;
  }

  private Pair<ActionGraph, BuildRuleResolver> createActionGraph(final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    final BuildRuleResolver resolver = new BuildRuleResolver(targetGraph, buildRuleGenerator);

    final int numberOfNodes = targetGraph.getNodes().size();
    final AtomicInteger processedNodes = new AtomicInteger(0);

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            try {
              resolver.requireRule(node.getBuildTarget());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }
            eventBus.post(ActionGraphEvent.processed(
                    processedNodes.incrementAndGet(),
                    numberOfNodes));
          }
        };
    bottomUpTraversal.traverse();

    Pair<ActionGraph, BuildRuleResolver> result = new Pair<>(
        new ActionGraph(resolver.getBuildRules()),
        resolver);
    eventBus.post(ActionGraphEvent.finished(started));
    return result;
  }

}
