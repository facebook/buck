/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.rules.resolver.impl;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.AbstractBuildRuleResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.concurrent.Parallelizer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Provides a mechanism for mapping between a {@link BuildTarget} and the {@link BuildRule} it
 * represents. Once parsing is complete, instances of this class can be considered immutable.
 */
public class SingleThreadedBuildRuleResolver extends AbstractBuildRuleResolver {

  private boolean isValid = true;
  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;
  private final CellProvider cellProvider;

  private final ConcurrentHashMap<BuildTarget, BuildRule> buildRuleIndex;
  private final BuildRuleResolverMetadataCache metadataCache;

  public SingleThreadedBuildRuleResolver(
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      CellProvider cellProvider) {
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;
    this.cellProvider = cellProvider;

    // We preallocate our maps to have this amount of slots to get rid of re-allocations
    int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);

    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        new BuildRuleResolverMetadataCache(this, this.targetGraph, initialCapacity);
  }

  @Override
  public Iterable<BuildRule> getBuildRules() {
    Preconditions.checkState(isValid);
    return Iterables.unmodifiableIterable(buildRuleIndex.values());
  }

  @Override
  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    Preconditions.checkState(isValid);
    return Optional.ofNullable(buildRuleIndex.get(buildTarget));
  }

  @Override
  public BuildRule computeIfAbsent(
      BuildTarget target, Function<BuildTarget, BuildRule> mappingFunction) {
    Preconditions.checkState(isValid);
    BuildRule rule = buildRuleIndex.get(target);
    if (rule != null) {
      return rule;
    }
    rule = mappingFunction.apply(target);
    Preconditions.checkState(
        // TODO(jakubzika): This should hold for flavored build targets as well.
        rule.getBuildTarget().getUnflavoredBuildTarget().equals(target.getUnflavoredBuildTarget()),
        "Computed rule for '%s' instead of '%s'.",
        rule.getBuildTarget(),
        target);
    BuildRule oldRule = buildRuleIndex.put(target, rule);
    Preconditions.checkState(
        // TODO(jakubzika): Eventually we should be able to remove the oldRule == rule part.
        // For now we need it to handle cases where a description adds a rule to the index before
        // returning it.
        oldRule == null || oldRule == rule,
        "Multiple rules created for target '%s':\n"
            + "new rule '%s' does not match existing rule '%s'.",
        target,
        rule,
        oldRule);
    return rule;
  }

  @Override
  public BuildRule requireRule(BuildTarget target) {
    Preconditions.checkState(isValid);
    return computeIfAbsent(
        target,
        (ignored) -> {
          TargetNode<?, ?> node = targetGraph.get(target);
          BuildRule rule = buildRuleGenerator.transform(cellProvider, targetGraph, this, node);
          Preconditions.checkState(
              // TODO(jakubzika): This should hold for flavored build targets as well.
              rule.getBuildTarget()
                  .getUnflavoredBuildTarget()
                  .equals(target.getUnflavoredBuildTarget()),
              "Description returned rule for '%s' instead of '%s'.",
              rule.getBuildTarget(),
              target);
          return rule;
        });
  }

  @Override
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass) {
    Preconditions.checkState(isValid);
    return metadataCache.requireMetadata(target, metadataClass);
  }

  /** Please use {@code computeIfAbsent} instead */
  @Deprecated
  @Override
  @VisibleForTesting
  public <T extends BuildRule> T addToIndex(T buildRule) {
    Preconditions.checkState(isValid);
    BuildRule oldValue = buildRuleIndex.put(buildRule.getBuildTarget(), buildRule);
    // Yuck! This is here to make it possible for a rule to depend on a flavor of itself but it
    // would be much much better if we just got rid of the BuildRuleResolver entirely.
    if (oldValue != null && oldValue != buildRule) {
      throw new IllegalStateException(
          "A build rule for this target has already been created: " + oldValue.getBuildTarget());
    }
    return buildRule;
  }

  @Override
  public Parallelizer getParallelizer() {
    Preconditions.checkState(isValid);
    return Parallelizer.SERIAL;
  }

  @Override
  public void invalidate() {
    isValid = false;
    buildRuleIndex.clear();
  }
}
