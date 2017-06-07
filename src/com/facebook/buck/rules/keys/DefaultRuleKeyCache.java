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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;
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

  // Allocates a new collection for storing reverse deps.
  private static final Function<Object, Collection<Object>> NEW_COLLECTION =
      o -> new ConcurrentLinkedQueue<>();

  private final Clock clock;

  /**
   * The underlying rule key cache. We use object identity for indexing.
   *
   * <p>NOTE: We intentionally use `ConcurrentHashMap` over Guava's `LoadingCache` for performance
   * reasons as the latter was a *lot* slower when invalidating nodes. Some differences are: a)
   * `LoadingCache` supports using object identity in it's keys by setting `weakKeys()`, whereas
   * `ConcurrentHashMap` does not. We work around this limitation by wrapping keys using the
   * `IdentityWrapper` helper class. b) `LoadingCache.get()` offers "at most once" execution
   * guarantees for the loading function for keys. `ConcurrentHashMap.computeIfAbsent()` does as
   * well, and we use it here, but it appears to use more coarse-grained locking and so is more
   * likely to block. As such, we wrap values in `Suppliers` so the computation is a very fast
   * `Supplier` allocation. c) The loading function called by `LoadingCache.get()` is re-entrant,
   * whereas `ConcurrentHashMap.computeIfAbsent()` is not. As the `RuleKeyCache.get*()` interfaces
   * must support re-entrant functions, we rely on wrapping values in memoizing `Suppliers` to
   * implement this behavior.
   */
  private final ConcurrentMap<IdentityWrapper<Object>, Supplier<V>> cache =
      new ConcurrentHashMap<>();

  /**
   * A map from cached nodes to their dependents. Used for invalidating the chain of transitive
   * dependents of a node.
   */
  private final ConcurrentMap<IdentityWrapper<Object>, Collection<Object>> dependentsIndex =
      new ConcurrentHashMap<>();

  /** A map for rule key inputs to nodes that use them. */
  private final ConcurrentMap<RuleKeyInput, Collection<Object>> inputsIndex =
      new ConcurrentHashMap<>();

  // Stats.
  private final LongAdder lookupCount = new LongAdder();
  private final LongAdder missCount = new LongAdder();
  private final LongAdder totalLoadTime = new LongAdder();
  private final LongAdder evictionCount = new LongAdder();

  public DefaultRuleKeyCache(Clock clock) {
    this.clock = clock;
  }

  public DefaultRuleKeyCache() {
    this(new DefaultClock());
  }

  private <K> V calculateNode(K node, Function<K, RuleKeyResult<V>> create) {
    Preconditions.checkArgument(
        node instanceof BuildRule ^ node instanceof RuleKeyAppendable,
        "%s must be one of either a `BuildRule` or `RuleKeyAppendable`",
        node.getClass());

    // Record start time for stats.
    long start = clock.nanoTime();

    RuleKeyResult<V> result = create.apply(node);
    for (Object dependency : result.deps) {
      dependentsIndex.computeIfAbsent(new IdentityWrapper<>(dependency), NEW_COLLECTION).add(node);
    }
    for (RuleKeyInput input : result.inputs) {
      inputsIndex.computeIfAbsent(input, NEW_COLLECTION).add(node);
    }

    // Update stats.
    long end = clock.nanoTime();
    totalLoadTime.add(end - start);
    missCount.increment();

    return result.result;
  }

  private <K> V getNode(K node, Function<K, RuleKeyResult<V>> create) {
    lookupCount.increment();
    return cache
        .computeIfAbsent(
            new IdentityWrapper<>(node),
            key -> Suppliers.memoize(() -> calculateNode(node, create)))
        .get();
  }

  @Nullable
  @Override
  public V get(BuildRule rule) {
    // Maybe put stats here?
    Supplier<V> supplier = cache.get(new IdentityWrapper<Object>(rule));
    if (supplier == null) {
      return null;
    }
    return supplier.get();
  }

  @Override
  public V get(BuildRule rule, Function<? super BuildRule, RuleKeyResult<V>> create) {
    return getNode(rule, create);
  }

  @Override
  public V get(
      RuleKeyAppendable appendable, Function<? super RuleKeyAppendable, RuleKeyResult<V>> create) {
    return getNode(appendable, create);
  }

  private boolean isCachedNode(Object object) {
    return cache.containsKey(new IdentityWrapper<>(object));
  }

  @VisibleForTesting
  public boolean isCached(BuildRule rule) {
    return isCachedNode(rule);
  }

  @VisibleForTesting
  public boolean isCached(RuleKeyAppendable appendable) {
    return isCachedNode(appendable);
  }

  /** Recursively invalidate nodes up the dependency tree. */
  private void invalidateNodes(Iterable<?> nodes) {
    for (Object node : nodes) {
      LOG.verbose("invalidating node %s", node);
      cache.remove(new IdentityWrapper<>(node));
      evictionCount.increment();
    }
    List<Iterable<Object>> dependents = new ArrayList<>();
    for (Object node : nodes) {
      Iterable<Object> nodeDependents = dependentsIndex.remove(new IdentityWrapper<>(node));
      if (nodeDependents != null) {
        dependents.add(nodeDependents);
      }
    }
    if (!dependents.isEmpty()) {
      invalidateNodes(Iterables.concat(dependents));
    }
  }

  /** Invalidate the given inputs and all their transitive dependents. */
  @Override
  public void invalidateInputs(Iterable<RuleKeyInput> inputs) {
    List<Iterable<Object>> nodes = new ArrayList<>();
    for (RuleKeyInput input : inputs) {
      LOG.verbose("invalidating input %s", input);
      Iterable<Object> inputNodes = inputsIndex.remove(input);
      if (inputNodes != null) {
        nodes.add(inputNodes);
      }
    }
    if (!nodes.isEmpty()) {
      invalidateNodes(Iterables.concat(nodes));
    }
  }

  @Override
  public void invalidateInputsMatchingRelativePath(Path path) {
    Preconditions.checkArgument(!path.isAbsolute());
    invalidateInputs(
        inputsIndex
            .keySet()
            .stream()
            .filter(input -> path.equals(input.getPath()))
            .collect(Collectors.toList()));
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
    dependentsIndex.clear();
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
    cache
        .entrySet()
        .forEach(
            entry -> {
              if (entry.getKey().delegate instanceof BuildRule) {
                builder.add(
                    new AbstractMap.SimpleEntry<>(
                        (BuildRule) entry.getKey().delegate, entry.getValue().get()));
              }
            });
    return builder.build();
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
