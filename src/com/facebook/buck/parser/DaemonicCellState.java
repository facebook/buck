/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteUpdateLock;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;

class DaemonicCellState {

  private static final Logger LOG = Logger.get(DaemonicCellState.class);

  /**
   * Cache of {@link BuildTarget} to some computed value at the {@link Cell} bases
   *
   * @param <T> the type of value cached
   */
  class Cache<K, T> {

    private final CellCacheType<K, T> type;

    /** Unbounded cache for all computed objects associated with build targets. */
    @GuardedBy("rawAndComputedNodesLock")
    public final ConcurrentMapCache<K, T> allComputedNodes =
        new ConcurrentMapCache<>(parsingThreads);

    /**
     * Provides access to all flavored build targets created and stored in all of the caches for a
     * given unflavored build target.
     *
     * <p>This map is used to locate all the build targets that need to be invalidated when a build
     * build file that produced those build targets has changed.
     */
    @GuardedBy("rawAndComputedNodesLock")
    private final SetMultimap<UnflavoredBuildTargetView, K> targetsCornucopia =
        HashMultimap.create();

    Cache(CellCacheType<K, T> type) {
      this.type = type;
    }

    private void invalidateFor(UnflavoredBuildTargetView target) {
      Set<K> keys = targetsCornucopia.removeAll(target);
      allComputedNodes.invalidateAll(keys);
    }

    public Optional<T> lookupComputedNode(K target) throws BuildTargetException {
      try (AutoCloseableLock readLock = rawAndComputedNodesLock.readLock()) {
        return Optional.ofNullable(allComputedNodes.getIfPresent(target));
      }
    }

    public T putComputedNodeIfNotPresent(K target, T targetNode) throws BuildTargetException {
      try (AutoCloseableLock writeLock = rawAndComputedNodesLock.writeLock()) {
        T updatedNode = allComputedNodes.putIfAbsentAndGet(target, targetNode);
        Preconditions.checkState(
            allRawNodeTargets.contains(type.keyToUnflavoredBuildTargetView.apply(target)),
            "Added %s to computed nodes, which isn't present in raw nodes",
            target);
        if (updatedNode.equals(targetNode)) {
          targetsCornucopia.put(type.keyToUnflavoredBuildTargetView.apply(target), target);
        }
        return updatedNode;
      }
    }
  }

  private final Path cellRoot;
  private final CanonicalCellName cellCanonicalName;
  private final AtomicReference<Cell> cell;

  /**
   * A mapping of a file path (usually explicit or implicit include) to a set of build file paths
   * that depend on the key file path. In the case of includes it indicates that build files in the
   * value include the file key file.
   *
   * <p>The purpose of this set is to invalidate build file manifests produced from the build files
   * that include changes files.
   */
  @GuardedBy("rawAndComputedNodesLock")
  private final SetMultimap<Path, Path> buildFileDependents;

  /**
   * Contains environment variables used during parsing of a particular build file.
   *
   * <p>The purpose of this map is to invalidate build file manifest if the values of environment
   * variables used during parsing of a build file that produced that build file manifest have
   * changed.
   */
  @GuardedBy("rawAndComputedNodesLock")
  private final Map<Path, ImmutableMap<String, Optional<String>>> buildFileEnv;

  /** Used as an unbounded cache to stored build file manifests by build file path. */
  @GuardedBy("rawAndComputedNodesLock")
  private final ConcurrentMapCache<Path, BuildFileManifest> allBuildFileManifests;

  /**
   * Contains all the unflavored build targets that were collected from all processed build file
   * manifests.
   *
   * <p>Used to verify that every build target added to individual caches ({@link
   * Cache#allComputedNodes}) is also in {@link #allBuildFileManifests}, as we use the latter to
   * handle invalidations.
   */
  @GuardedBy("rawAndComputedNodesLock")
  private final Set<UnflavoredBuildTargetView> allRawNodeTargets;

  /** Type-safe accessor to one of state caches */
  static class CellCacheType<K, T> {
    private final Function<DaemonicCellState, Cache<K, T>> getCache;
    private final Function<K, UnconfiguredBuildTargetView> keyToUnconfiguredBuildTargetView;
    private final Function<K, UnflavoredBuildTargetView> keyToUnflavoredBuildTargetView;

    CellCacheType(
        Function<DaemonicCellState, Cache<K, T>> getCache,
        Function<K, UnconfiguredBuildTargetView> keyToUnconfiguredBuildTargetView,
        Function<K, UnflavoredBuildTargetView> keyToUnflavoredBuildTargetView) {
      this.getCache = getCache;
      this.keyToUnconfiguredBuildTargetView = keyToUnconfiguredBuildTargetView;
      this.keyToUnflavoredBuildTargetView = keyToUnflavoredBuildTargetView;
    }

    UnconfiguredBuildTargetView convertToUnconfiguredBuildTargetView(K key) {
      return keyToUnconfiguredBuildTargetView.apply(key);
    }
  }

  static final CellCacheType<UnconfiguredBuildTargetView, RawTargetNode>
      RAW_TARGET_NODE_CACHE_TYPE =
          new CellCacheType<>(
              state -> state.rawTargetNodeCache,
              k -> k,
              UnconfiguredBuildTargetView::getUnflavoredBuildTargetView);
  static final CellCacheType<BuildTarget, TargetNode<?>> TARGET_NODE_CACHE_TYPE =
      new CellCacheType<>(
          state -> state.targetNodeCache,
          BuildTarget::getUnconfiguredBuildTargetView,
          BuildTarget::getUnflavoredBuildTarget);

  private Cache<?, ?>[] typedNodeCaches() {
    return new Cache[] {targetNodeCache, rawTargetNodeCache};
  }

  /** Keeps caches by the object type supported by the cache. */
  private final Cache<BuildTarget, TargetNode<?>> targetNodeCache;

  private final Cache<UnconfiguredBuildTargetView, RawTargetNode> rawTargetNodeCache;

  private final AutoCloseableReadWriteUpdateLock rawAndComputedNodesLock;
  private final int parsingThreads;

  DaemonicCellState(Cell cell, int parsingThreads) {
    this.cell = new AtomicReference<>(cell);
    this.parsingThreads = parsingThreads;
    this.cellRoot = cell.getRoot();
    this.cellCanonicalName = cell.getCanonicalName();
    this.buildFileDependents = HashMultimap.create();
    this.buildFileEnv = new HashMap<>();
    this.allBuildFileManifests = new ConcurrentMapCache<>(parsingThreads);
    this.allRawNodeTargets = new HashSet<>();
    this.rawAndComputedNodesLock = new AutoCloseableReadWriteUpdateLock();
    this.targetNodeCache = new Cache<>(TARGET_NODE_CACHE_TYPE);
    this.rawTargetNodeCache = new Cache<>(RAW_TARGET_NODE_CACHE_TYPE);
  }

  // TODO(mzlee): Only needed for invalidateBasedOn which does not have access to cell metadata
  Cell getCell() {
    return Objects.requireNonNull(cell.get());
  }

  Path getCellRoot() {
    return cellRoot;
  }

  public <K, T> Cache<K, T> getCache(CellCacheType<K, T> type) {
    return type.getCache.apply(this);
  }

  Optional<BuildFileManifest> lookupBuildFileManifest(Path buildFile) {
    try (AutoCloseableLock readLock = rawAndComputedNodesLock.readLock()) {
      return Optional.ofNullable(allBuildFileManifests.getIfPresent(buildFile));
    }
  }

  BuildFileManifest putBuildFileManifestIfNotPresent(
      Path buildFile,
      BuildFileManifest buildFileManifest,
      ImmutableSet<Path> dependentsOfEveryNode,
      ImmutableMap<String, Optional<String>> env) {
    try (AutoCloseableLock writeLock = rawAndComputedNodesLock.writeLock()) {
      BuildFileManifest updated =
          allBuildFileManifests.putIfAbsentAndGet(buildFile, buildFileManifest);
      for (Map<String, Object> node : updated.getTargets().values()) {
        allRawNodeTargets.add(
            UnflavoredBuildTargetFactory.createFromRawNode(
                cellRoot, cellCanonicalName, node, buildFile));
      }
      buildFileEnv.put(buildFile, env);
      if (updated == buildFileManifest) {
        // We now know all the nodes. They all implicitly depend on everything in
        // the "dependentsOfEveryNode" set.
        for (Path dependent : dependentsOfEveryNode) {
          buildFileDependents.put(dependent, buildFile);
        }
      }
      return updated;
    }
  }

  int invalidatePath(Path path) {
    try (AutoCloseableLock writeLock = rawAndComputedNodesLock.writeLock()) {
      int invalidatedRawNodes = 0;
      BuildFileManifest buildFileManifest = allBuildFileManifests.getIfPresent(path);
      if (buildFileManifest != null) {
        ImmutableMap<String, Map<String, Object>> rawNodes = buildFileManifest.getTargets();
        // Increment the counter
        invalidatedRawNodes = rawNodes.size();
        for (Map<String, Object> rawNode : rawNodes.values()) {
          UnflavoredBuildTargetView target =
              UnflavoredBuildTargetFactory.createFromRawNode(
                  cellRoot, cellCanonicalName, rawNode, path);
          LOG.debug("Invalidating target for path %s: %s", path, target);
          for (Cache<?, ?> cache : typedNodeCaches()) {
            cache.invalidateFor(target);
          }
          allRawNodeTargets.remove(target);
        }
        allBuildFileManifests.invalidate(path);
      }

      // We may have been given a file that other build files depend on. Iteratively remove those.
      Iterable<Path> dependents = buildFileDependents.get(path);
      LOG.verbose("Invalidating dependents for path %s: %s", path, dependents);
      for (Path dependent : dependents) {
        if (dependent.equals(path)) {
          continue;
        }
        invalidatedRawNodes += invalidatePath(dependent);
      }
      buildFileDependents.removeAll(path);
      buildFileEnv.remove(path);

      return invalidatedRawNodes;
    }
  }

  Optional<MapDifference<String, String>> invalidateIfEnvHasChanged(Cell cell, Path buildFile) {
    // Invalidate if env vars have changed.
    ImmutableMap<String, Optional<String>> usedEnv;
    try (AutoCloseableLock readLock = rawAndComputedNodesLock.readLock()) {
      usedEnv = buildFileEnv.get(buildFile);
    }
    if (usedEnv == null) {
      this.cell.set(cell);
      return Optional.empty();
    }
    for (Map.Entry<String, Optional<String>> ent : usedEnv.entrySet()) {
      Optional<String> value =
          Optional.ofNullable(cell.getBuckConfig().getEnvironment().get(ent.getKey()));
      if (!value.equals(ent.getValue())) {
        LOG.verbose("invalidating for env change: %s (%s != %s)", buildFile, value, ent.getValue());
        invalidatePath(buildFile);
        this.cell.set(cell);
        return Optional.of(
            Maps.difference(
                value.map(v -> ImmutableMap.of(ent.getKey(), v)).orElse(ImmutableMap.of()),
                ent.getValue()
                    .map(v -> ImmutableMap.of(ent.getKey(), v))
                    .orElse(ImmutableMap.of())));
      }
    }
    return Optional.empty();
  }

  /** @return {@code true} if the given path has dependencies that are present in the given set. */
  boolean pathDependentPresentIn(Path path, Set<Path> buildFiles) {
    return !Collections.disjoint(buildFileDependents.get(cellRoot.resolve(path)), buildFiles);
  }
}
