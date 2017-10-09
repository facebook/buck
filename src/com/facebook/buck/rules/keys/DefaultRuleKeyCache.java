/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * A {@link com.facebook.buck.rules.RuleKey} cache used by a {@link RuleKeyFactory}. Inputs and
 * dependencies of cached rule keys are tracked to allow for invalidations based on changed inputs.
 * As such, this cache is usable between multiple build runs.
 *
 * @param <V> The rule key type.
 */
public class DefaultRuleKeyCache<V> implements RuleKeyCache<V> {

  private static final Logger LOG = Logger.get(DefaultRuleKeyCache.class);

  private final Clock clock;

  /**
   * The underlying rule key cache. We use object identity for indexing.
   *
   * <p>All modifications to the Nodes are synchronized by using the compute* functions on
   * ConcurrentHashMap.
   */
  private final ConcurrentHashMap<IdentityWrapper<Object>, Node<Object, V>> cache =
      new ConcurrentHashMap<>();

  /** A map for rule key inputs to nodes that use them. */
  private final ConcurrentHashMap<RuleKeyInput, Stream.Builder<Object>> inputsIndex =
      new ConcurrentHashMap<>();

  // Stats.
  private final LongAdder lookupCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final LongAdder totalLoadTime = new LongAdder();
  private final LongAdder evictionCount = new LongAdder();

  DefaultRuleKeyCache(Clock clock) {
    this.clock = clock;
  }

  public DefaultRuleKeyCache() {
    this(new DefaultClock());
  }

  private <K> V calculateNode(K node, Function<K, RuleKeyResult<V>> create) {
    Preconditions.checkArgument(
        node instanceof BuildRule ^ node instanceof AddsToRuleKey,
        "%s must be one of either a `BuildRule` or `AddsToRuleKey`",
        node.getClass());

    // Record start time for stats.
    long start = clock.nanoTime();

    RuleKeyResult<V> result = create.apply(node);
    for (Object dependency : result.deps) {
      cache.compute(
          new IdentityWrapper<>(dependency),
          (key, value) -> {
            if (value == null) {
              value = new Node<>();
            }
            value.dependents.add(node);
            return value;
          });
    }
    for (RuleKeyInput input : result.inputs) {
      inputsIndex.compute(
          input,
          (key, builder) -> {
            if (builder == null) {
              builder = Stream.builder();
            }
            builder.add(node);
            return builder;
          });
    }

    // Update stats.
    long end = clock.nanoTime();
    totalLoadTime.add(end - start);
    missCount.increment();

    return result.result;
  }

  private <K> V getNode(K node, Function<K, RuleKeyResult<V>> create) {
    lookupCount.increment();
    Supplier<V> supplier =
        cache.compute(
                new IdentityWrapper<>(node),
                (key, value) -> {
                  if (value == null) {
                    value = new Node<>();
                  }
                  if (value.value == null) {
                    value.value = MoreSuppliers.memoize(() -> calculateNode(node, create));
                  }
                  return value;
                })
            .value;
    return Preconditions.checkNotNull(supplier).get();
  }

  @Nullable
  @Override
  public V get(BuildRule rule) {
    // Maybe put stats here?
    Node<Object, V> node = cache.get(new IdentityWrapper<Object>(rule));
    if (node != null && node.value != null) {
      return Preconditions.checkNotNull(node.value).get();
    }
    return null;
  }

  @Override
  public V get(BuildRule rule, Function<? super BuildRule, RuleKeyResult<V>> create) {
    return getNode(rule, create);
  }

  @Override
  public V get(AddsToRuleKey appendable, Function<? super AddsToRuleKey, RuleKeyResult<V>> create) {
    return getNode(appendable, create);
  }

  private boolean isCachedNode(Object object) {
    return cache.containsKey(new IdentityWrapper<>(object));
  }

  @VisibleForTesting
  boolean isCached(BuildRule rule) {
    return isCachedNode(rule);
  }

  @VisibleForTesting
  boolean isCached(AddsToRuleKey appendable) {
    return isCachedNode(appendable);
  }

  /** Recursively invalidate nodes up the dependency tree. */
  private void invalidateNodes(Stream<Object> nodes) {
    List<Stream<Object>> dependents = new ArrayList<>();
    nodes.forEach(
        key -> {
          Node<Object, V> node = cache.remove(new IdentityWrapper<>(key));
          // This node may have already been removed due to being someone else's reverse dependency.
          if (node != null) {
            LOG.verbose("invalidating node %s", key);
            dependents.add(node.dependents.build());
            evictionCount.increment();
          }
        });
    if (!dependents.isEmpty()) {
      invalidateNodes(dependents.stream().flatMap(x -> x));
    }
  }

  /** Invalidate the given inputs and all their transitive dependents. */
  @Override
  public void invalidateInputs(Iterable<RuleKeyInput> inputs) {
    List<Stream<Object>> nodes = new ArrayList<>();
    for (RuleKeyInput input : inputs) {
      LOG.verbose("invalidating input %s", input);
      Stream.Builder<Object> inputNodes = inputsIndex.remove(input);
      if (inputNodes != null) {
        nodes.add(inputNodes.build());
      }
    }
    if (!nodes.isEmpty()) {
      invalidateNodes(nodes.stream().flatMap(x -> x));
    }
  }

  /**
   * Invalidate all inputs *not* from the given {@link ProjectFilesystem}s and their transitive
   * dependents.
   */
  @Override
  public void invalidateAllExceptFilesystems(ImmutableSet<ProjectFilesystem> filesystems) {
    if (filesystems.isEmpty()) {
      invalidateAll();
    } else {
      invalidateInputs(
          inputsIndex
              .keySet()
              .stream()
              .filter(input -> !filesystems.contains(input.getFilesystem()))
              .collect(Collectors.toList()));
    }
  }

  /**
   * Invalidate all inputs from a given {@link ProjectFilesystem} and their transitive dependents.
   */
  @Override
  public void invalidateFilesystem(ProjectFilesystem filesystem) {
    invalidateInputs(
        inputsIndex
            .keySet()
            .stream()
            .filter(input -> filesystem.equals(input.getFilesystem()))
            .collect(Collectors.toList()));
  }

  /** Invalidate everything in the cache. */
  @Override
  public void invalidateAll() {
    cache.clear();
    inputsIndex.clear();
  }

  @Override
  public CacheStats getStats() {
    long missCount = this.missCount.longValue();
    return new CacheStats(
        lookupCount.longValue() - missCount,
        missCount,
        missCount,
        0L,
        totalLoadTime.longValue(),
        evictionCount.longValue());
  }

  @Override
  public ImmutableList<Map.Entry<BuildRule, V>> getCachedBuildRules() {
    ImmutableList.Builder<Map.Entry<BuildRule, V>> builder = ImmutableList.builder();
    cache.forEach(
        (key, value) -> {
          if (key.delegate instanceof BuildRule) {
            Supplier<V> supplier = value.value;
            if (supplier != null) {
              builder.add(new AbstractMap.SimpleEntry<>((BuildRule) key.delegate, supplier.get()));
            }
          }
        });
    return builder.build();
  }

  private static final class Node<T, V> {
    /**
     * Accumulator of nodes that depends on this one. Used to invalidate those nodes when this node
     * is invalidated.
     */
    private final Stream.Builder<T> dependents;

    /**
     * The cached value, stored in a memoized supplier. A memoized supplier is used to allow the
     * value computation to be serialized separately from the lock in the ConcurrentHashMap that the
     * nodes are stored in.
     *
     * <p>This value is nullable because the instance can be created in response to recording a
     * dependent.
     */
    @Nullable private volatile Supplier<V> value;

    public Node() {
      this.dependents = Stream.builder();
      this.value = null;
    }
  }

  /**
   * A wrapper class which uses identity equality and hash code. Intended to wrap keys used in a
   * map.
   */
  private static final class IdentityWrapper<T> {

    private final T delegate;

    private IdentityWrapper(T delegate) {
      this.delegate = delegate;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(delegate);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof DefaultRuleKeyCache.IdentityWrapper)) {
        return false;
      }
      IdentityWrapper<?> other = (IdentityWrapper<?>) obj;
      return delegate == other.delegate;
    }
  }
}
