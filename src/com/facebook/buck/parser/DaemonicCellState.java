/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.PackageFileManifest;
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
    @GuardedBy("cachesLock")
    public final ConcurrentMapCache<K, T> allComputedNodes =
        new ConcurrentMapCache<>(parsingThreads);

    /**
     * Provides access to all flavored build targets created and stored in all of the caches for a
     * given unflavored build target.
     *
     * <p>This map is used to locate all the build targets that need to be invalidated when a build
     * build file that produced those build targets has changed.
     */
    @GuardedBy("cachesLock")
    private final SetMultimap<UnflavoredBuildTarget, K> targetsCornucopia = HashMultimap.create();

    Cache(CellCacheType<K, T> type) {
      this.type = type;
    }

    private void invalidateFor(UnflavoredBuildTarget target) {
      Set<K> keys = targetsCornucopia.removeAll(target);
      allComputedNodes.invalidateAll(keys);
    }

    public Optional<T> lookupComputedNode(K target) throws BuildTargetException {
      try (AutoCloseableLock readLock = cachesLock.readLock()) {
        return Optional.ofNullable(allComputedNodes.getIfPresent(target));
      }
    }

    public T putComputedNodeIfNotPresent(K target, T targetNode) throws BuildTargetException {
      try (AutoCloseableLock writeLock = cachesLock.writeLock()) {
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

  private final AbsPath cellRoot;
  private final CanonicalCellName cellCanonicalName;
  private final AtomicReference<Cell> cell;

  /**
   * A mapping from dependent files (typically .bzl or PACKAGE files) to all build files which
   * include that dependent file explicitly or transitively. This allows us to track which build
   * files to invalidate when a dependent file changes.
   */
  @GuardedBy("cachesLock")
  private final SetMultimap<AbsPath, AbsPath> buildFileDependents;

  /**
   * A mapping from dependent files (typically .bzl files) to all PACKAGE files which include that
   * dependent file explicitly or transitively. This allows us to track which PACKAGE files to
   * invalidate when a dependent file changes.
   */
  @GuardedBy("cachesLock")
  private final SetMultimap<AbsPath, AbsPath> packageFileDependents;

  /**
   * Contains environment variables used during parsing of a particular build file.
   *
   * <p>The purpose of this map is to invalidate build file manifest if the values of environment
   * variables used during parsing of a build file that produced that build file manifest have
   * changed.
   */
  @GuardedBy("cachesLock")
  private final Map<AbsPath, ImmutableMap<String, Optional<String>>> buildFileEnv;

  /** Used as an unbounded cache to stored build file manifests by build file path. */
  @GuardedBy("cachesLock")
  private final ConcurrentMapCache<AbsPath, BuildFileManifest> allBuildFileManifests;

  /** Used as an unbounded cache to stored package file manifests by package file path. */
  @GuardedBy("cachesLock")
  private final ConcurrentMapCache<AbsPath, PackageFileManifest> allPackageFileManifests;

  /**
   * Contains all the unflavored build targets that were collected from all processed build file
   * manifests.
   *
   * <p>Used to verify that every build target added to individual caches ({@link
   * Cache#allComputedNodes}) is also in {@link #allBuildFileManifests}, as we use the latter to
   * handle invalidations.
   */
  @GuardedBy("cachesLock")
  private final Set<UnflavoredBuildTarget> allRawNodeTargets;

  /** Type-safe accessor to one of state caches */
  static class CellCacheType<K, T> {
    private final Function<DaemonicCellState, Cache<K, T>> getCache;
    private final Function<K, UnconfiguredBuildTarget> keyToUnconfiguredBuildTarget;
    private final Function<K, UnflavoredBuildTarget> keyToUnflavoredBuildTargetView;

    CellCacheType(
        Function<DaemonicCellState, Cache<K, T>> getCache,
        Function<K, UnconfiguredBuildTarget> keyToUnconfiguredBuildTarget,
        Function<K, UnflavoredBuildTarget> keyToUnflavoredBuildTargetView) {
      this.getCache = getCache;
      this.keyToUnconfiguredBuildTarget = keyToUnconfiguredBuildTarget;
      this.keyToUnflavoredBuildTargetView = keyToUnflavoredBuildTargetView;
    }

    UnconfiguredBuildTarget convertToUnconfiguredBuildTargetView(K key) {
      return keyToUnconfiguredBuildTarget.apply(key);
    }
  }

  static final CellCacheType<UnconfiguredBuildTarget, UnconfiguredTargetNode>
      RAW_TARGET_NODE_CACHE_TYPE =
          new CellCacheType<>(
              state -> state.rawTargetNodeCache,
              k -> k,
              UnconfiguredBuildTarget::getUnflavoredBuildTarget);
  static final CellCacheType<BuildTarget, TargetNodeMaybeIncompatible> TARGET_NODE_CACHE_TYPE =
      new CellCacheType<>(
          state -> state.targetNodeCache,
          BuildTarget::getUnconfiguredBuildTarget,
          BuildTarget::getUnflavoredBuildTarget);

  private Cache<?, ?>[] typedNodeCaches() {
    return new Cache[] {targetNodeCache, rawTargetNodeCache};
  }

  /** Keeps caches by the object type supported by the cache. */
  private final Cache<BuildTarget, TargetNodeMaybeIncompatible> targetNodeCache;

  private final Cache<UnconfiguredBuildTarget, UnconfiguredTargetNode> rawTargetNodeCache;

  private final AutoCloseableReadWriteUpdateLock cachesLock;
  private final int parsingThreads;

  DaemonicCellState(Cell cell, int parsingThreads) {
    this.cell = new AtomicReference<>(cell);
    this.parsingThreads = parsingThreads;
    this.cellRoot = cell.getRoot();
    this.cellCanonicalName = cell.getCanonicalName();
    this.buildFileDependents = HashMultimap.create();
    this.packageFileDependents = HashMultimap.create();
    this.buildFileEnv = new HashMap<>();
    this.allBuildFileManifests = new ConcurrentMapCache<>(parsingThreads);
    this.allPackageFileManifests = new ConcurrentMapCache<>(parsingThreads);
    this.allRawNodeTargets = new HashSet<>();
    this.cachesLock = new AutoCloseableReadWriteUpdateLock();
    this.targetNodeCache = new Cache<>(TARGET_NODE_CACHE_TYPE);
    this.rawTargetNodeCache = new Cache<>(RAW_TARGET_NODE_CACHE_TYPE);
  }

  // TODO(mzlee): Only needed for invalidateBasedOn which does not have access to cell metadata
  Cell getCell() {
    return Objects.requireNonNull(cell.get());
  }

  AbsPath getCellRoot() {
    return cellRoot;
  }

  public <K, T> Cache<K, T> getCache(CellCacheType<K, T> type) {
    return type.getCache.apply(this);
  }

  Optional<BuildFileManifest> lookupBuildFileManifest(AbsPath buildFile) {
    try (AutoCloseableLock readLock = cachesLock.readLock()) {
      return Optional.ofNullable(allBuildFileManifests.getIfPresent(buildFile));
    }
  }

  BuildFileManifest putBuildFileManifestIfNotPresent(
      AbsPath buildFile,
      BuildFileManifest buildFileManifest,
      ImmutableSet<AbsPath> dependentsOfEveryNode,
      ImmutableMap<String, Optional<String>> env) {
    try (AutoCloseableLock writeLock = cachesLock.writeLock()) {
      BuildFileManifest updated =
          allBuildFileManifests.putIfAbsentAndGet(buildFile, buildFileManifest);
      for (Map<String, Object> node : updated.getTargets().values()) {
        allRawNodeTargets.add(
            UnflavoredBuildTargetFactory.createFromRawNode(
                cellRoot.getPath(), cellCanonicalName, node, buildFile.getPath()));
      }
      buildFileEnv.put(buildFile, env);
      if (updated == buildFileManifest) {
        // We now know all the nodes. They all implicitly depend on everything in
        // the "dependentsOfEveryNode" set.
        for (AbsPath dependent : dependentsOfEveryNode) {
          buildFileDependents.put(dependent, buildFile);
        }
      }
      return updated;
    }
  }

  Optional<PackageFileManifest> lookupPackageFileManifest(AbsPath packageFile) {
    try (AutoCloseableLock readLock = cachesLock.readLock()) {
      return Optional.ofNullable(allPackageFileManifests.getIfPresent(packageFile));
    }
  }

  PackageFileManifest putPackageFileManifestIfNotPresent(
      AbsPath packageFile,
      PackageFileManifest packageFileManifest,
      ImmutableSet<AbsPath> packageDependents,
      ImmutableMap<String, Optional<String>> env) {
    try (AutoCloseableLock writeLock = cachesLock.writeLock()) {
      PackageFileManifest updated =
          allPackageFileManifests.putIfAbsentAndGet(packageFile, packageFileManifest);
      buildFileEnv.put(packageFile, env);
      if (updated == packageFileManifest) {
        // The package file will depend on all dependents and we keep a reverse mapping to know
        // which package files to invalidate if a dependent changes.
        for (AbsPath dependent : packageDependents) {
          this.packageFileDependents.put(dependent, packageFile);
        }
      }
      return updated;
    }
  }

  /**
   * Invalidates all target nodes defined in {@param path}. Optionally also invalidates the build
   * targets {@link UnflavoredBuildTarget} depending on {@param invalidateBuildTargets}.
   *
   * @return The number of invalidated nodes.
   */
  int invalidateNodesInPath(AbsPath path, boolean invalidateBuildTargets) {
    try (AutoCloseableLock writeLock = cachesLock.writeLock()) {
      int invalidatedRawNodes = 0;
      BuildFileManifest buildFileManifest = allBuildFileManifests.getIfPresent(path);
      if (buildFileManifest != null) {
        ImmutableMap<String, ImmutableMap<String, Object>> rawNodes =
            buildFileManifest.getTargets();
        // Increment the counter
        invalidatedRawNodes = rawNodes.size();
        for (Map<String, Object> rawNode : rawNodes.values()) {
          UnflavoredBuildTarget target =
              UnflavoredBuildTargetFactory.createFromRawNode(
                  cellRoot.getPath(), cellCanonicalName, rawNode, path.getPath());
          LOG.debug("Invalidating target for path %s: %s", path, target);
          for (Cache<?, ?> cache : typedNodeCaches()) {
            cache.invalidateFor(target);
          }
          if (invalidateBuildTargets) {
            allRawNodeTargets.remove(target);
          }
        }
      }
      return invalidatedRawNodes;
    }
  }

  /**
   * Invalidates all cached content based on the {@param path}, returning the count of invalidated
   * raw nodes.
   *
   * <p>The path may be a reference to any file. In the case of a:
   *
   * <ul>
   *   <li>build file, it invalidates the cached build manifest, cached nodes and build targets
   *   <li>package file, it invalidates the cached package manifest and cached nodes that depend on
   *       the package file
   *   <li>bzl file, it invalidates any dependent build files and package files, which they
   *       themselves invalidate recursively, invalidated any relevant cached content.
   * </ul>
   *
   * @param path Absolute path to the file for which to invalidate all cached content.
   * @return Count of all invalidated raw nodes for the path
   */
  int invalidatePath(AbsPath path) {
    try (AutoCloseableLock writeLock = cachesLock.writeLock()) {
      // If `path` is a build file with a valid entry in `allBuildFileManifests`, we also want to
      // invalidate the build targets in the manifest.
      int invalidatedRawNodes = invalidateNodesInPath(path, true);

      allBuildFileManifests.invalidate(path);
      allPackageFileManifests.invalidate(path);

      // We may have been given a file that other build files depend on. Invalidate accordingly.
      Set<AbsPath> dependents = buildFileDependents.get(path);
      boolean isPackageFile = PackagePipeline.isPackageFile(path.getPath());
      LOG.verbose("Invalidating dependents for path %s: %s", path, dependents);
      for (AbsPath dependent : dependents) {
        if (dependent.equals(path)) {
          continue;
        }
        if (isPackageFile) {
          // Typically, the dependents of PACKAGE files are build files. If there is a valid entry
          // for `dependent` in `allBuildFileManifests`, invalidate the cached nodes, but not the
          // build targets contained within in.
          invalidatedRawNodes += invalidateNodesInPath(dependent, false);
        } else {
          // Recursively invalidate all cached content based on `dependent`.
          invalidatedRawNodes += invalidatePath(dependent);
        }
      }
      if (!isPackageFile) {
        // Package files do not invalidate the build file (as the build file does not need to be
        // re-parsed). This means the dependents of the package remain intact.
        buildFileDependents.removeAll(path);
        buildFileEnv.remove(path);
      }

      // We may have been given a file that package files depends on. Iteratively invalidate those
      // package files.
      dependents = packageFileDependents.get(path);
      for (AbsPath dependent : dependents) {
        if (dependent.equals(path)) {
          continue;
        }
        invalidatedRawNodes += invalidatePath(dependent);
      }
      packageFileDependents.removeAll(path);

      return invalidatedRawNodes;
    }
  }

  Optional<MapDifference<String, String>> invalidateIfEnvHasChanged(Cell cell, AbsPath buildFile) {
    // Invalidate if env vars have changed.
    ImmutableMap<String, Optional<String>> usedEnv;
    try (AutoCloseableLock readLock = cachesLock.readLock()) {
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
  boolean pathDependentPresentIn(Path path, Set<AbsPath> buildFiles) {
    return !Collections.disjoint(buildFileDependents.get(cellRoot.resolve(path)), buildFiles);
  }
}
