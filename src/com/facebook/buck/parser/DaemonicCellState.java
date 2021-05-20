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
import com.facebook.buck.core.filesystems.FileName;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.concurrent.AutoCloseableLocked;
import com.facebook.buck.util.concurrent.AutoCloseableReadLocked;
import com.facebook.buck.util.concurrent.AutoCloseableWriteLocked;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
    public final ConcurrentMapCache<K, T> allComputedNodes = new ConcurrentMapCache<>();

    /**
     * Provides access to all flavored build targets created and stored in all of the caches for a
     * given unflavored build target.
     *
     * <p>This map is used to locate all the build targets that need to be invalidated when a build
     * build file that produced those build targets has changed.
     */
    private final ConcurrentHashMap<UnflavoredBuildTarget, Set<K>> targetsCornucopia =
        new ConcurrentHashMap<>();

    Cache(CellCacheType<K, T> type) {
      this.type = type;
    }

    // Assumes caller has a write lock on `cachesLock`.
    private void invalidateFor(UnflavoredBuildTarget target, AutoCloseableWriteLocked locked) {
      locked.markUsed();

      Set<K> keys = targetsCornucopia.remove(target);
      if (keys != null) {
        allComputedNodes.invalidateAll(keys);
      }
    }

    public Optional<T> lookupComputedNode(K target, DaemonicParserValidationToken validationToken)
        throws BuildTargetException {
      T node = allComputedNodes.getIfPresent(target);
      return node != null && locks.isValid(validationToken) ? Optional.of(node) : Optional.empty();
    }

    public T putComputedNodeIfNotPresent(K target, T targetNode, AutoCloseableLocked readLock)
        throws BuildTargetException {
      readLock.markUsed();

      UnflavoredBuildTarget unflavoredBuildTarget =
          type.keyToUnflavoredBuildTargetView.apply(target);

      ForwardRelPath buildFile =
          unflavoredBuildTarget
              .getCellRelativeBasePath()
              .getPath()
              .resolve(DaemonicCellState.this.buildFileName);

      T updatedNode = allComputedNodes.putIfAbsentAndGet(target, targetNode);
      BuildFileManifest buildFileManifest = allBuildFileManifests.getIfPresent(buildFile);
      Preconditions.checkState(
          buildFileManifest != null
              && buildFileManifest.getTargets().containsKey(unflavoredBuildTarget.getLocalName()),
          "Added %s to computed nodes, which isn't present in raw nodes",
          target);
      if (updatedNode.equals(targetNode)) {
        targetsCornucopia
            .computeIfAbsent(
                type.keyToUnflavoredBuildTargetView.apply(target),
                t -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(target);
      }
      return updatedNode;
    }
  }

  private final AbsPath cellRoot;
  private final CanonicalCellName cellCanonicalName;
  private final Cell cell;
  private final FileName buildFileName;

  /**
   * A mapping from dependent files (typically .bzl or PACKAGE files) to all build files which
   * include that dependent file explicitly or transitively. This allows us to track which build
   * files to invalidate when a dependent file changes.
   */
  private final ConcurrentHashMap<AbsPath, Set<AbsPath>> buildFileDependents;

  /**
   * A mapping from dependent files (typically .bzl files) to all PACKAGE files which include that
   * dependent file explicitly or transitively. This allows us to track which PACKAGE files to
   * invalidate when a dependent file changes.
   */
  private final ConcurrentHashMap<AbsPath, Set<AbsPath>> packageFileDependents;

  /** Used as an unbounded cache to stored build file manifests by build file path. */
  private final ConcurrentMapCache<ForwardRelPath, BuildFileManifest> allBuildFileManifests;

  /** Used as an unbounded cache to stored package file manifests by package file path. */
  private final ConcurrentMapCache<ForwardRelPath, PackageFileManifest> allPackageFileManifests;

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

  private final DaemonicParserStateLocks locks;

  DaemonicCellState(Cell cell, DaemonicParserStateLocks locks) {
    this.cell = cell;
    this.cellRoot = cell.getRoot();
    this.cellCanonicalName = cell.getCanonicalName();
    this.buildFileName = cell.getBuckConfigView(ParserConfig.class).getBuildFileName();
    this.locks = locks;
    this.buildFileDependents = new ConcurrentHashMap<>();
    this.packageFileDependents = new ConcurrentHashMap<>();
    this.allBuildFileManifests = new ConcurrentMapCache<>();
    this.allPackageFileManifests = new ConcurrentMapCache<>();
    this.targetNodeCache = new Cache<>(TARGET_NODE_CACHE_TYPE);
    this.rawTargetNodeCache = new Cache<>(RAW_TARGET_NODE_CACHE_TYPE);
  }

  // TODO(mzlee): Only needed for invalidateBasedOn which does not have access to cell metadata
  Cell getCell() {
    return cell;
  }

  AbsPath getCellRoot() {
    return cellRoot;
  }

  public <K, T> Cache<K, T> getCache(CellCacheType<K, T> type) {
    return type.getCache.apply(this);
  }

  Optional<BuildFileManifest> lookupBuildFileManifest(
      ForwardRelPath buildFile, DaemonicParserValidationToken validationToken) {
    BuildFileManifest manifest = allBuildFileManifests.getIfPresent(buildFile);
    return manifest != null && locks.isValid(validationToken)
        ? Optional.of(manifest)
        : Optional.empty();
  }

  BuildFileManifest putBuildFileManifestIfNotPresent(
      ForwardRelPath buildFile,
      BuildFileManifest buildFileManifest,
      ImmutableSet<AbsPath> dependentsOfEveryNode,
      AutoCloseableReadLocked locked) {
    locked.markUsed();

    AbsPath buildFileAbs = cellRoot.resolve(buildFile);

    BuildFileManifest updated =
        allBuildFileManifests.putIfAbsentAndGet(buildFile, buildFileManifest);
    if (updated == buildFileManifest) {
      // We now know all the nodes. They all implicitly depend on everything in
      // the "dependentsOfEveryNode" set.
      for (AbsPath dependent : dependentsOfEveryNode) {
        buildFileDependents
            .computeIfAbsent(dependent, p -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(buildFileAbs);
      }
    }
    return updated;
  }

  @VisibleForTesting
  BuildFileManifest putBuildFileManifestIfNotPresentForTest(
      ForwardRelPath buildFile,
      BuildFileManifest buildFileManifest,
      ImmutableSet<AbsPath> dependentsOfEveryNode) {
    try (AutoCloseableReadLocked locked = locks.cachesLock.lockRead()) {
      return putBuildFileManifestIfNotPresent(
          buildFile, buildFileManifest, dependentsOfEveryNode, locked);
    }
  }

  Optional<PackageFileManifest> lookupPackageFileManifest(
      ForwardRelPath packageFile, DaemonicParserValidationToken validationToken) {
    PackageFileManifest manifest = allPackageFileManifests.getIfPresent(packageFile);
    return manifest != null && locks.isValid(validationToken)
        ? Optional.of(manifest)
        : Optional.empty();
  }

  PackageFileManifest putPackageFileManifestIfNotPresent(
      ForwardRelPath packageFile,
      PackageFileManifest packageFileManifest,
      ImmutableSet<AbsPath> packageDependents,
      AutoCloseableReadLocked locked) {
    locked.markUsed();

    AbsPath packageFileAbs = cellRoot.resolve(packageFile);

    PackageFileManifest updated =
        allPackageFileManifests.putIfAbsentAndGet(packageFile, packageFileManifest);
    if (updated == packageFileManifest) {
      // The package file will depend on all dependents and we keep a reverse mapping to know
      // which package files to invalidate if a dependent changes.
      for (AbsPath dependent : packageDependents) {
        this.packageFileDependents
            .computeIfAbsent(dependent, p -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(packageFileAbs);
      }
    }
    return updated;
  }

  @VisibleForTesting
  PackageFileManifest putPackageFileManifestIfNotPresentForTest(
      ForwardRelPath packageFile,
      PackageFileManifest packageFileManifest,
      ImmutableSet<AbsPath> packageDependents) {
    try (AutoCloseableReadLocked locked = locks.cachesLock.lockRead()) {
      return putPackageFileManifestIfNotPresent(
          packageFile, packageFileManifest, packageDependents, locked);
    }
  }

  /**
   * Invalidates all target nodes defined in {@param path}. Optionally also invalidates the build
   * targets {@link UnflavoredBuildTarget} depending on {@param invalidateBuildTargets}.
   *
   * @return The number of invalidated nodes.
   */
  private int invalidateNodesInPath(AbsPath path, AutoCloseableWriteLocked locked) {
    locked.markUsed();

    RelPath relPath = path.removePrefixIfStartsWith(cellRoot);

    int invalidatedRawNodes = 0;
    if (relPath != null) {
      BuildFileManifest buildFileManifest =
          allBuildFileManifests.getIfPresent(ForwardRelPath.ofRelPath(relPath));
      if (buildFileManifest != null) {
        TwoArraysImmutableHashMap<String, RawTargetNode> rawNodes = buildFileManifest.getTargets();
        // Increment the counter
        invalidatedRawNodes = rawNodes.size();
        for (RawTargetNode rawNode : rawNodes.values()) {
          UnflavoredBuildTarget target =
              UnflavoredBuildTargetFactory.createFromRawNode(
                  cellRoot, cellCanonicalName, rawNode, path);
          LOG.debug("Invalidating target for path %s: %s", path, target);
          for (Cache<?, ?> cache : typedNodeCaches()) {
            cache.invalidateFor(target, locked);
          }
        }
      }
    }
    return invalidatedRawNodes;
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
   * @param invalidateManifests Whether to invalidate cached manifests at {@code path}.
   * @return Count of all invalidated raw nodes for the path
   */
  int invalidatePath(AbsPath path, boolean invalidateManifests, AutoCloseableWriteLocked locked) {

    // If `path` is a build file with a valid entry in `allBuildFileManifests`, we also want to
    // invalidate the build targets in the manifest.
    int invalidatedRawNodes = invalidateNodesInPath(path, locked);

    if (invalidateManifests) {
      RelPath relPath = path.removePrefixIfStartsWith(cellRoot);
      if (relPath != null) {
        allBuildFileManifests.invalidate(ForwardRelPath.ofRelPath(relPath));
        allPackageFileManifests.invalidate(ForwardRelPath.ofRelPath(relPath));
      }
    }

    // We may have been given a file that other build files depend on. Invalidate accordingly.
    Set<AbsPath> dependents = buildFileDependents.getOrDefault(path, ImmutableSet.of());
    boolean pathIsPackageFile = PackagePipeline.isPackageFile(path);
    LOG.verbose("Invalidating dependents for path %s: %s", path, dependents);
    for (AbsPath dependent : dependents) {
      if (dependent.equals(path)) {
        continue;
      }
      if (pathIsPackageFile) {
        // Typically, the dependents of PACKAGE files are build files. If there is a valid entry
        // for `dependent` in `allBuildFileManifests`, invalidate the cached nodes, but not the
        // build targets contained within in.
        invalidatedRawNodes += invalidateNodesInPath(dependent, locked);
      } else {
        // Recursively invalidate all cached content based on `dependent`.
        invalidatedRawNodes += invalidatePath(dependent, true, locked);
      }
    }
    if (!pathIsPackageFile) {
      // Package files do not invalidate the build file (as the build file does not need to be
      // re-parsed). This means the dependents of the package remain intact.
      buildFileDependents.remove(path);
    }

    // We may have been given a file that package files depends on. Iteratively invalidate those
    // package files.
    dependents = packageFileDependents.getOrDefault(path, ImmutableSet.of());
    for (AbsPath dependent : dependents) {
      if (dependent.equals(path)) {
        continue;
      }
      if (pathIsPackageFile) {
        // Package files depend on parent package files (if a valid parent exists), but the
        // invalidation of a parent does not invalidate the manifest of a child package file.
        invalidatedRawNodes += invalidatePath(dependent, false, locked);
      } else {
        invalidatedRawNodes += invalidatePath(dependent, true, locked);
      }
    }
    // Dependents of package files are build files and other package files, neither of which
    // we want to invalidate.
    if (!pathIsPackageFile) {
      packageFileDependents.remove(path);
    }

    return invalidatedRawNodes;
  }

  @VisibleForTesting
  int invalidatePathForTest(AbsPath path, boolean invalidateManifests) {
    try (AutoCloseableWriteLocked locked = locks.cachesLock.lockWrite()) {
      return invalidatePath(path, invalidateManifests, locked);
    }
  }

  /** @return {@code true} if the given path has dependencies that are present in the given set. */
  boolean pathDependentPresentIn(
      ForwardRelPath path, Set<AbsPath> buildFiles, AutoCloseableWriteLocked locked) {
    locked.markUsed();

    return !Collections.disjoint(
        buildFileDependents.getOrDefault(cellRoot.resolve(path), ImmutableSet.of()), buildFiles);
  }
}
