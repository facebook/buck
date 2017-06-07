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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Provides a mechanism for mapping between a {@link BuildTarget} and the {@link BuildRule} it
 * represents. Once parsing is complete, instances of this class can be considered immutable.
 */
public class BuildRuleResolver {

  private final TargetGraph targetGraph;
  private final TargetNodeToBuildRuleTransformer buildRuleGenerator;

  /** Event bus for reporting performance information. Will likely be null in unit tests. */
  @Nullable private final BuckEventBus eventBus;

  private final ConcurrentHashMap<BuildTarget, BuildRule> buildRuleIndex;
  private final LoadingCache<Pair<BuildTarget, Class<?>>, Optional<?>> metadataCache;

  public BuildRuleResolver(
      TargetGraph targetGraph, TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    this(targetGraph, buildRuleGenerator, null);
  }

  public BuildRuleResolver(
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      @Nullable BuckEventBus eventBus) {
    this.targetGraph = targetGraph;
    this.buildRuleGenerator = buildRuleGenerator;
    this.eventBus = eventBus;

    // We preallocate our maps to have this amount of slots to get rid of re-allocations
    final int initialCapacity = (int) (targetGraph.getNodes().size() * 5 * 1.1);

    this.buildRuleIndex = new ConcurrentHashMap<>(initialCapacity);
    this.metadataCache =
        CacheBuilder.newBuilder()
            .initialCapacity(initialCapacity)
            .build(
                new CacheLoader<Pair<BuildTarget, Class<?>>, Optional<?>>() {
                  @Override
                  public Optional<?> load(Pair<BuildTarget, Class<?>> key) throws Exception {
                    TargetNode<?, ?> node = BuildRuleResolver.this.targetGraph.get(key.getFirst());
                    return load(node, key.getSecond());
                  }

                  @SuppressWarnings("unchecked")
                  private <T, U> Optional<U> load(TargetNode<T, ?> node, Class<U> metadataClass)
                      throws NoSuchBuildTargetException {
                    T arg = node.getConstructorArg();
                    if (metadataClass.isAssignableFrom(arg.getClass())) {
                      return Optional.of(metadataClass.cast(arg));
                    }

                    Description<?> description = node.getDescription();
                    if (!(description instanceof MetadataProvidingDescription)) {
                      return Optional.empty();
                    }
                    MetadataProvidingDescription<T> metadataProvidingDescription =
                        (MetadataProvidingDescription<T>) description;
                    return metadataProvidingDescription.createMetadata(
                        node.getBuildTarget(),
                        BuildRuleResolver.this,
                        arg,
                        node.getSelectedVersions(),
                        metadataClass);
                  }
                });
  }

  /** @return an unmodifiable view of the rules in the index */
  public Iterable<BuildRule> getBuildRules() {
    return Iterables.unmodifiableIterable(buildRuleIndex.values());
  }

  private <T> T fromNullable(BuildTarget target, @Nullable T rule) {
    if (rule == null) {
      throw new HumanReadableException("Rule for target '%s' could not be resolved.", target);
    }
    return rule;
  }

  /** Returns the {@link BuildRule} with the {@code buildTarget}. */
  public BuildRule getRule(BuildTarget buildTarget) {
    return fromNullable(buildTarget, buildRuleIndex.get(buildTarget));
  }

  public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
    return Optional.ofNullable(buildRuleIndex.get(buildTarget));
  }

  public BuildRule requireRule(BuildTarget target) throws NoSuchBuildTargetException {
    BuildRule rule = buildRuleIndex.get(target);
    if (rule != null) {
      return rule;
    }
    TargetNode<?, ?> node = targetGraph.get(target);
    rule = buildRuleGenerator.transform(targetGraph, this, node);
    Preconditions.checkState(
        // TODO(jakubzika): This should hold for flavored build targets as well.
        rule.getBuildTarget().getUnflavoredBuildTarget().equals(target.getUnflavoredBuildTarget()),
        "Description returned rule for '%s' instead of '%s'.",
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

  public ImmutableSortedSet<BuildRule> requireAllRules(Iterable<BuildTarget> buildTargets)
      throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();
    for (BuildTarget target : buildTargets) {
      rules.add(requireRule(target));
    }
    return rules.build();
  }

  @SuppressWarnings("unchecked")
  public <T> Optional<T> requireMetadata(BuildTarget target, Class<T> metadataClass)
      throws NoSuchBuildTargetException {
    try {
      return (Optional<T>)
          metadataCache.get(new Pair<BuildTarget, Class<?>>(target, metadataClass));
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), NoSuchBuildTargetException.class);
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public <T> Optional<T> getRuleOptionalWithType(BuildTarget buildTarget, Class<T> cls) {
    BuildRule rule = buildRuleIndex.get(buildTarget);
    if (rule != null) {
      if (cls.isInstance(rule)) {
        return Optional.of((T) rule);
      } else {
        throw new HumanReadableException(
            "Rule for target '%s' is present but not of expected type %s (got %s)",
            buildTarget, cls, rule.getClass());
      }
    }
    return Optional.empty();
  }

  public <T> T getRuleWithType(BuildTarget buildTarget, Class<T> cls) {
    return fromNullable(buildTarget, getRuleOptionalWithType(buildTarget, cls).orElse(null));
  }

  public ImmutableSortedSet<BuildRule> getAllRules(Iterable<BuildTarget> targets) {
    return getAllRulesStream(targets).toImmutableSortedSet(Ordering.natural());
  }

  public RichStream<BuildRule> getAllRulesStream(Iterable<BuildTarget> targets) {
    return RichStream.from(targets).map(this::getRule);
  }

  /**
   * Adds to the index a mapping from {@code buildRule}'s target to itself and returns {@code
   * buildRule}.
   */
  @VisibleForTesting
  public <T extends BuildRule> T addToIndex(T buildRule) {
    BuildRule oldValue = buildRuleIndex.put(buildRule.getBuildTarget(), buildRule);
    // Yuck! This is here to make it possible for a rule to depend on a flavor of itself but it
    // would be much much better if we just got rid of the BuildRuleResolver entirely.
    if (oldValue != null && oldValue != buildRule) {
      throw new IllegalStateException(
          "A build rule for this target has already been created: " + oldValue.getBuildTarget());
    }
    return buildRule;
  }

  /** Adds an iterable of build rules to the index. */
  public <T extends BuildRule, C extends Iterable<T>> C addAllToIndex(C buildRules) {
    for (T buildRule : buildRules) {
      addToIndex(buildRule);
    }
    return buildRules;
  }

  @Nullable
  public BuckEventBus getEventBus() {
    return eventBus;
  }
}
