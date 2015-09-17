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
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;

import javax.annotation.Nullable;

public class TargetGraphToActionGraph implements TargetGraphTransformer {

  private static final Logger LOG = Logger.get(TargetGraphToActionGraph.class);

  private final BuckEventBus eventBus;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final FileHashCache fileHashCache;
  private volatile int hashOfTargetGraph;

  @Nullable
  private volatile ActionGraph actionGraph;
  @Nullable
  private volatile ImmutableMap<ProjectFilesystem, BuildRuleResolver> ruleResolvers;

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

    final LoadingCache<ProjectFilesystem, RepoSpecificData> mappedRepos =
        CacheBuilder.newBuilder().build(
            new CacheLoader<ProjectFilesystem, RepoSpecificData>() {
              @Override
              public RepoSpecificData load(ProjectFilesystem filesystem) {
                return new RepoSpecificData(fileHashCache);
              }
            });

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            ProjectFilesystem filesystem = node.getRuleFactoryParams().getProjectFilesystem();
            RepoSpecificData data = mappedRepos.getUnchecked(filesystem);

            BuildRule rule;
            try {
              rule = buildRuleGenerator.transform(
                  targetGraph,
                  data.ruleResolver,
                  node,
                  data.ruleKeyBuilderFactory);
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }

            // Check whether a rule with this build target already exists. This is possible
            // if we create a new build rule during graph enhancement, and the user asks to
            // build the same build rule. The returned rule may have a different name from the
            // target node.
            Optional<BuildRule> existingRule =
                data.ruleResolver.getRuleOptional(rule.getBuildTarget());
            Preconditions.checkState(
                !existingRule.isPresent() || existingRule.get().equals(rule));
            if (!existingRule.isPresent()) {
              data.ruleResolver.addToIndex(rule);
            }
          }
        };
    bottomUpTraversal.traverse();

    ImmutableMap.Builder<ProjectFilesystem, BuildRuleResolver> resolvers = ImmutableMap.builder();
    ImmutableSet.Builder<BuildRule> allRules = ImmutableSet.builder();
    for (Map.Entry<ProjectFilesystem, RepoSpecificData> entry : mappedRepos.asMap().entrySet()) {
      BuildRuleResolver ruleResolver = entry.getValue().ruleResolver;
      resolvers.put(entry.getKey(), ruleResolver);
      allRules.addAll(ruleResolver.getBuildRules());
    }

    ruleResolvers = resolvers.build();
    ActionGraph result = new ActionGraph(allRules.build());
    eventBus.post(ActionGraphEvent.finished(started));
    return result;
  }

  public ImmutableMap<ProjectFilesystem, BuildRuleResolver> getRuleResolvers() {
    return Preconditions.checkNotNull(ruleResolvers);
  }

  private static class RepoSpecificData {
    public BuildRuleResolver ruleResolver = new BuildRuleResolver();
    public RuleKeyBuilderFactory ruleKeyBuilderFactory;

    public RepoSpecificData(FileHashCache fileHashCache) {
      this.ruleResolver = new BuildRuleResolver();
      SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
      this.ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);
    }
  }

}
