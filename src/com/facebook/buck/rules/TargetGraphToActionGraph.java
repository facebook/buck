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
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

public class TargetGraphToActionGraph implements TargetGraphTransformer {

  private static final Logger LOG = Logger.get(TargetGraphToActionGraph.class);

  private final BuckEventBus eventBus;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final FileHashCache fileHashCache;
  @Nullable
  private volatile ActionGraph actionGraph;
  private volatile int hashOfTargetGraph;
  private final BuildRuleResolver ruleResolver = new BuildRuleResolver();

  public TargetGraphToActionGraph(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      FileHashCache fileHashCache) {
    this.eventBus = eventBus;
    this.buildRuleGenerator = buildRuleGenerator;
    this.fileHashCache = fileHashCache;
  }

  @Override
  public synchronized ActionGraph apply(TargetGraph targetGraph) {
    if (actionGraph != null) {
      if (targetGraph.hashCode() == hashOfTargetGraph) {
        return actionGraph;
      }
      LOG.info("Flushing cached action graph. May be a performance hit.");
    }

    actionGraph = createActionGraph(targetGraph);
    hashOfTargetGraph = targetGraph.hashCode();

    return actionGraph;
  }

  private ActionGraph createActionGraph(final TargetGraph targetGraph) {
    ActionGraphEvent.Started started = ActionGraphEvent.started();
    eventBus.post(started);

    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    final RuleKeyBuilderFactory ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(
        fileHashCache,
        pathResolver);

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            BuildRule rule;
            try {
              rule = buildRuleGenerator.transform(
                  targetGraph,
                  ruleResolver,
                  node,
                  ruleKeyBuilderFactory);
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }

            // Check whether a rule with this build target already exists. This is possible
            // if we create a new build rule during graph enhancement, and the user asks to
            // build the same build rule. The returned rule may have a different name from the
            // target node.
            Optional<BuildRule> existingRule =
                ruleResolver.getRuleOptional(rule.getBuildTarget());
            Preconditions.checkState(
                !existingRule.isPresent() || existingRule.get().equals(rule));
            if (!existingRule.isPresent()) {
              ruleResolver.addToIndex(rule);
            }
          }

          @Override
          public ActionGraph getResult() {
            return new ActionGraph(ruleResolver.getBuildRules());
          }

        };
    bottomUpTraversal.traverse();
    ActionGraph result = bottomUpTraversal.getResult();
    eventBus.post(ActionGraphEvent.finished(started));
    return result;
  }

  public BuildRuleResolver getRuleResolver() {
    return ruleResolver;
  }

}
