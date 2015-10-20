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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

public class TargetGraphToActionGraph implements TargetGraphTransformer {

  private static final Logger LOG = Logger.get(TargetGraphToActionGraph.class);

  private final BuckEventBus eventBus;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final FileHashCache fileHashCache;
  private final BuildRuleResolver resolver;
  private volatile int hashOfTargetGraph;

  @Nullable
  private volatile ActionGraph actionGraph;


  public TargetGraphToActionGraph(
      BuckEventBus eventBus,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      FileHashCache fileHashCache) {
    this.eventBus = eventBus;
    this.buildRuleGenerator = buildRuleGenerator;
    this.fileHashCache = fileHashCache;

    this.resolver = new BuildRuleResolver();
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

    // I think this could be a little more verbose, but I'm not quite sure how.
    final LoadingCache<ProjectFilesystem, RuleKeyBuilderFactory>
        ruleKeyBuilderFactories = CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, RuleKeyBuilderFactory>() {
          @Override
          public RuleKeyBuilderFactory load(ProjectFilesystem filesystem) throws Exception {

            StackedFileHashCache cellHashCache = new StackedFileHashCache(
                ImmutableList.of(
                    fileHashCache,
                    new DefaultFileHashCache(filesystem)));

            return new DefaultRuleKeyBuilderFactory(
                cellHashCache,
                new SourcePathResolver(resolver));
            }
        }
    );

    final int numberOfNodes = targetGraph.getNodes().size();
    final AtomicInteger processedNodes = new AtomicInteger(0);

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            RuleKeyBuilderFactory data = ruleKeyBuilderFactories.getUnchecked(
                node.getRuleFactoryParams().getProjectFilesystem());

            BuildRule rule;
            try {
              rule = buildRuleGenerator.transform(
                  targetGraph,
                  resolver,
                  node,
                  data);
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }

            // Check whether a rule with this build target already exists. This is possible
            // if we create a new build rule during graph enhancement, and the user asks to
            // build the same build rule. The returned rule may have a different name from the
            // target node.
            Optional<BuildRule> existingRule =
                resolver.getRuleOptional(rule.getBuildTarget());
            Preconditions.checkState(
                !existingRule.isPresent() || existingRule.get().equals(rule));
            if (!existingRule.isPresent()) {
              resolver.addToIndex(rule);
            }

            eventBus.post(ActionGraphEvent.processed(
                    processedNodes.incrementAndGet(),
                    numberOfNodes));
          }
        };
    bottomUpTraversal.traverse();

    ActionGraph result = new ActionGraph(resolver.getBuildRules());
    eventBus.post(ActionGraphEvent.finished(started));
    return result;
  }

  public BuildRuleResolver getRuleResolver() {
    return resolver;
  }
}
