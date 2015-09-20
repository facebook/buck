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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;

public class TargetGraphToActionGraph implements TargetGraphTransformer {

  private static final Logger LOG = Logger.get(TargetGraphToActionGraph.class);

  private final BuckEventBus eventBus;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final FileHashCache fileHashCache;
  private final LoadingCache<ProjectFilesystem, BuildRuleResolver> ruleResolvers;
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

    this.ruleResolvers = CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, BuildRuleResolver>() {
          @Override
          public BuildRuleResolver load(ProjectFilesystem key) throws Exception {
            return new BuildRuleResolver();
          }
        }
    );
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
    final LoadingCache<CellFilesystemResolver, Pair<BuildRuleResolver, RuleKeyBuilderFactory>>
        cellSpecificData = CacheBuilder.newBuilder().build(
        new CacheLoader<CellFilesystemResolver, Pair<BuildRuleResolver, RuleKeyBuilderFactory>>() {
          @Override
          public Pair<BuildRuleResolver, RuleKeyBuilderFactory> load(
              CellFilesystemResolver cellNameResolver) throws Exception {
            BuildRuleResolver ruleResolver = new BuildRuleResolverView(cellNameResolver);

            StackedFileHashCache cellHashCache = new StackedFileHashCache(
                ImmutableList.of(
                    fileHashCache,
                    new DefaultFileHashCache(cellNameResolver.getFilesystem())));

            RuleKeyBuilderFactory factory = new DefaultRuleKeyBuilderFactory(
                cellHashCache,
                new SourcePathResolver(ruleResolver));

            return new Pair<>(ruleResolver, factory);
          }
        }
    );

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(targetGraph) {

          @Override
          public void visit(TargetNode<?> node) {
            Pair<BuildRuleResolver, RuleKeyBuilderFactory> data =
                cellSpecificData.getUnchecked(node.getCellFilesystemResolver());

            BuildRule rule;
            try {
              rule = buildRuleGenerator.transform(
                  targetGraph,
                  data.getFirst(),
                  node,
                  data.getSecond());
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }

            // Check whether a rule with this build target already exists. This is possible
            // if we create a new build rule during graph enhancement, and the user asks to
            // build the same build rule. The returned rule may have a different name from the
            // target node.
            BuildRuleResolver ruleResolver = data.getFirst();
            Optional<BuildRule> existingRule =
                ruleResolver.getRuleOptional(rule.getBuildTarget());
            Preconditions.checkState(
                !existingRule.isPresent() || existingRule.get().equals(rule));
            if (!existingRule.isPresent()) {
              ruleResolver.addToIndex(rule);
            }
          }
        };
    bottomUpTraversal.traverse();

    ImmutableSet.Builder<BuildRule> allRules = ImmutableSet.builder();
    for (BuildRuleResolver resolver : ruleResolvers.asMap().values()) {
      allRules.addAll(resolver.getBuildRules());
    }

    ActionGraph result = new ActionGraph(allRules.build());
    eventBus.post(ActionGraphEvent.finished(started));
    return result;
  }

  public ImmutableMap<ProjectFilesystem, BuildRuleResolver> getRuleResolvers() {
    Preconditions.checkNotNull(ruleResolvers);
    return ImmutableMap.copyOf(ruleResolvers.asMap());
  }

  private class BuildRuleResolverView extends BuildRuleResolver {
    private final CellFilesystemResolver nameResolver;

    public BuildRuleResolverView(CellFilesystemResolver nameResolver) {
      this.nameResolver = nameResolver;
    }

    @Override
    public <T extends BuildRule> T addToIndex(T buildRule) {
      ProjectFilesystem filesystem = buildRule.getProjectFilesystem();
      BuildRuleResolver toUse = ruleResolvers.getUnchecked(filesystem);
      return toUse.addToIndex(buildRule);
    }

    @Override
    public BuildRule getRule(BuildTarget target) {
      ProjectFilesystem filesystem = nameResolver.apply(target.getRepository());
      BuildRuleResolver toUse = ruleResolvers.getUnchecked(filesystem);

      BuildTarget withoutCell = target.withoutRepository();
      return toUse.getRule(withoutCell);
    }

    @Override
    public Iterable<BuildRule> getBuildRules() {
      return FluentIterable.from(ruleResolvers.asMap().values())
          .transformAndConcat(
              new Function<BuildRuleResolver, Iterable<BuildRule>>() {
                @Override
                public Iterable<BuildRule> apply(BuildRuleResolver input) {
                  return input.getBuildRules();
                }
              });
    }

    @Override
    public Optional<BuildRule> getRuleOptional(BuildTarget target) {
      ProjectFilesystem filesystem = nameResolver.apply(target.getRepository());
      BuildRuleResolver toUse = ruleResolvers.getUnchecked(filesystem);

      return toUse.getRuleOptional(target.withoutRepository());
    }

    @Override
    public <T extends BuildRule> Optional<T> getRuleOptionalWithType(
        BuildTarget target, Class<T> cls) {
      ProjectFilesystem filesystem = nameResolver.apply(target.getRepository());
      BuildRuleResolver toUse = ruleResolvers.getUnchecked(filesystem);

      return toUse.getRuleOptionalWithType(target, cls);
    }
  }
}
