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
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.Counter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.io.watchman.WatchmanWatcherOneBigEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.concurrent.AutoCloseableLocked;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteLock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Persistent parsing data, that can exist between invocations of the {@link Parser}. All public
 * methods that cause build files to be read must be guarded by calls to {@link
 * #invalidateIfProjectBuildFileParserStateChanged(Cell)} in order to ensure that state is
 * maintained correctly.
 */
public class DaemonicParserState {

  private static final Logger LOG = Logger.get(DaemonicParserState.class);

  // pattern all implicit include paths from build file includes should match
  // this should be kept in sync with pattern used in buck.py
  private static final Pattern INCLUDE_PATH_PATTERN = Pattern.compile("^([A-Za-z0-9_]*)//(.*)$");

  /** Stateless view of caches on object that conforms to {@link PipelineNodeCache.Cache}. */
  private class DaemonicCacheView<K, T> implements PipelineNodeCache.Cache<K, T> {

    protected final DaemonicCellState.CellCacheType<K, T> type;

    private DaemonicCacheView(DaemonicCellState.CellCacheType<K, T> type) {
      this.type = type;
    }

    @Override
    public Optional<T> lookupComputedNode(Cell cell, K target, BuckEventBus eventBus)
        throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);

      DaemonicCellState.Cache<K, T> state = getCache(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupComputedNode(target);
    }

    @Override
    public T putComputedNodeIfNotPresent(
        Cell cell, K target, T targetNode, boolean targetIsConfiguration, BuckEventBus eventBus)
        throws BuildTargetException {

      // Verify we don't invalidate the build file at this point, as, at this point, we should have
      // already called `lookupComputedNode` which should have done any invalidation.
      Preconditions.checkState(
          !invalidateIfProjectBuildFileParserStateChanged(cell),
          "Unexpected invalidation due to build file parser state change for %s %s",
          cell.getRoot(),
          target);
      AbsPath buildFile =
          cell.getBuckConfigView(ParserConfig.class)
              .getAbsolutePathToBuildFileUnsafe(
                  cell, type.convertToUnconfiguredBuildTargetView(target));

      if (targetIsConfiguration) {
        configurationBuildFiles.add(buildFile);
      }

      return getOrCreateCache(cell).putComputedNodeIfNotPresent(target, targetNode);
    }

    private @Nullable DaemonicCellState.Cache<K, T> getCache(Cell cell) {
      DaemonicCellState cellState = getCellState(cell);
      if (cellState == null) {
        return null;
      }
      return cellState.getCache(type);
    }

    private DaemonicCellState.Cache<K, T> getOrCreateCache(Cell cell) {
      return getOrCreateCellState(cell).getCache(type);
    }
  }

  /** Stateless view of caches on object that conforms to {@link PipelineNodeCache.Cache}. */
  private class DaemonicRawCacheView
      implements PipelineNodeCache.Cache<AbsPath, BuildFileManifest> {

    @Override
    public Optional<BuildFileManifest> lookupComputedNode(
        Cell cell, AbsPath buildFile, BuckEventBus eventBus) throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);

      DaemonicCellState state = getCellState(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupBuildFileManifest(buildFile);
    }

    /**
     * Insert item into the cache if it was not already there. The cache will also strip any meta
     * entries from the raw nodes (these are intended for the cache as they contain information
     * about what other files to invalidate entries on).
     *
     * @return previous nodes for the file if the cache contained it, new ones otherwise.
     */
    @Override
    public BuildFileManifest putComputedNodeIfNotPresent(
        Cell cell,
        AbsPath buildFile,
        BuildFileManifest manifest,
        boolean targetIsConfiguration,
        BuckEventBus eventBus)
        throws BuildTargetException {
      // Technically this leads to inconsistent state if the state change happens after rawNodes
      // were computed, but before we reach the synchronized section here, however that's a problem
      // we already have, as we don't invalidate any nodes that have been retrieved from the cache
      // (and so the partially-constructed graph will contain stale nodes if the cache was
      // invalidated mid-way through the parse).
      invalidateIfProjectBuildFileParserStateChanged(cell);

      ImmutableSet.Builder<AbsPath> dependentsOfEveryNode = ImmutableSet.builder();

      addAllIncludes(dependentsOfEveryNode, manifest.getIncludes(), cell);

      if (cell.getBuckConfig().getView(ParserConfig.class).getEnablePackageFiles()) {
        // Add the PACKAGE file in the build file's directory, regardless of whether they currently
        // exist. If a PACKAGE file is added, or a parent PACKAGE file is modified/added we need to
        // invalidate all relevant nodes.
        AbsPath packageFile = PackagePipeline.getPackageFileFromBuildFile(cell, buildFile);
        dependentsOfEveryNode.add(packageFile);
      }

      return getOrCreateCellState(cell)
          .putBuildFileManifestIfNotPresent(buildFile, manifest, dependentsOfEveryNode.build());
    }
  }

  /** Stateless view of caches on object that conforms to {@link PipelineNodeCache.Cache}. */
  private class DaemonicPackageCache
      implements PipelineNodeCache.Cache<AbsPath, PackageFileManifest> {

    @Override
    public Optional<PackageFileManifest> lookupComputedNode(
        Cell cell, AbsPath packageFile, BuckEventBus eventBus) throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);

      DaemonicCellState state = getCellState(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupPackageFileManifest(packageFile);
    }

    /**
     * Insert item into the cache if it was not already there.
     *
     * @return previous manifest for the file if the cache contained it, new ones otherwise.
     */
    @Override
    public PackageFileManifest putComputedNodeIfNotPresent(
        Cell cell,
        AbsPath packageFile,
        PackageFileManifest manifest,
        boolean targetIsConfiguration,
        BuckEventBus eventBus)
        throws BuildTargetException {
      ImmutableSet.Builder<AbsPath> packageDependents = ImmutableSet.builder();

      addAllIncludes(packageDependents, manifest.getIncludes(), cell);

      // Package files may depend on their parent PACKAGE file.
      Optional<AbsPath> parentPackageFile = PackagePipeline.getParentPackageFile(cell, packageFile);
      parentPackageFile.ifPresent(path -> packageDependents.add(path));

      return getOrCreateCellState(cell)
          .putPackageFileManifestIfNotPresent(packageFile, manifest, packageDependents.build());
    }
  }

  /** Add all the includes from the manifest and Buck defaults. */
  private static void addAllIncludes(
      ImmutableSet.Builder<AbsPath> dependents, ImmutableSet<String> manifestIncludes, Cell cell) {
    manifestIncludes.forEach(
        includedPath -> dependents.add(cell.getFilesystem().resolve(includedPath)));

    // We also know that the all manifests depend on the default includes for the cell.
    // Note: This is a bad assumption. While both the project build file and package parsers set
    // the default includes of the ParserConfig, it is not required and this assumption may not
    // always hold.
    BuckConfig buckConfig = cell.getBuckConfig();
    Iterable<String> defaultIncludes = buckConfig.getView(ParserConfig.class).getDefaultIncludes();
    for (String include : defaultIncludes) {
      dependents.add(resolveIncludePath(cell, include, cell.getCellPathResolver()));
    }
  }

  /**
   * Resolves a path of an include string like {@code repo//foo/macro_defs} to a filesystem path.
   */
  private static AbsPath resolveIncludePath(
      Cell cell, String include, CellPathResolver cellPathResolver) {
    // Default includes are given as "cell//path/to/file". They look like targets
    // but they are not. However, I bet someone will try and treat it like a
    // target, so find the owning cell if necessary, and then fully resolve
    // the path against the owning cell's root.
    Matcher matcher = INCLUDE_PATH_PATTERN.matcher(include);
    Preconditions.checkState(matcher.matches());
    String cellName = matcher.group(1);
    CanonicalCellName canonicalCellName =
        (Objects.isNull(cellName) || cellName.isEmpty())
            ? CanonicalCellName.rootCell()
            : cellPathResolver.getCellNameResolver().getName(Optional.of(cellName));

    String includePath = matcher.group(2);
    return cellPathResolver
        .getCellPath(canonicalCellName)
        .map(cellPath -> cellPath.resolve(includePath))
        .orElseGet(() -> cell.getFilesystem().resolve(includePath));
  }

  /**
   * The set of {@link Cell} instances that have been seen by this state. This information is used
   * for cache invalidation. Please see {@link #invalidateBasedOn(WatchmanPathEvent)} for example
   * usage.
   */
  private final ConcurrentMap<CanonicalCellName, DaemonicCellState> cellToDaemonicState;

  private final DaemonicCacheView<BuildTarget, TargetNodeMaybeIncompatible> targetNodeCache =
      new DaemonicCacheView<>(DaemonicCellState.TARGET_NODE_CACHE_TYPE);
  private final DaemonicCacheView<UnconfiguredBuildTarget, UnconfiguredTargetNode>
      rawTargetNodeCache = new DaemonicCacheView<>(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);

  /**
   * Build files that contain configuration targets.
   *
   * <p>Changes in configuration targets are handled differently from the changes in build targets.
   * Whenever there is a change in configuration targets the state in all cells is reset. Parser
   * state doesn't provide information about dependencies among build rules and configuration rules
   * and changes in configuration rules can affect build targets (including build targets in other
   * cells).
   */
  // TODO: remove logic around this field when proper tracking of dependencies on
  // configuration rules is implemented
  private final Set<AbsPath> configurationBuildFiles = ConcurrentHashMap.newKeySet();

  private final DaemonicRawCacheView rawNodeCache;

  private final DaemonicPackageCache packageFileCache;

  private final DaemonicParserStateCounters counters;

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * The default includes used by the previous run of the parser in each cell (the key is the cell's
   * root path). If this value changes, then we need to invalidate all the caches.
   */
  private ConcurrentHashMap<CanonicalCellName, ImmutableList<String>> defaultIncludesByCellName;

  private final AutoCloseableReadWriteLock cachedStateLock;
  private final AutoCloseableReadWriteLock cellStateLock;
  private final DaemonicParserStateLocks locks;

  public DaemonicParserState() {
    this.counters = new DaemonicParserStateCounters();
    this.buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new FilesystemBackedBuildFileTree(
                        cell.getFilesystem(),
                        cell.getBuckConfigView(ParserConfig.class).getBuildFileName());
                  }
                });
    this.defaultIncludesByCellName = new ConcurrentHashMap<>();
    this.cellToDaemonicState = new ConcurrentHashMap<>();

    this.rawNodeCache = new DaemonicRawCacheView();
    this.packageFileCache = new DaemonicPackageCache();

    this.cachedStateLock = new AutoCloseableReadWriteLock();
    this.cellStateLock = new AutoCloseableReadWriteLock();
    this.locks = new DaemonicParserStateLocks();
  }

  LoadingCache<Cell, BuildFileTree> getBuildFileTrees() {
    return buildFileTrees;
  }

  /** Type-safe accessor to one of state caches */
  static final class CacheType<K, T> {
    private final Function<DaemonicParserState, DaemonicCacheView<K, T>> getCacheView;

    public CacheType(Function<DaemonicParserState, DaemonicCacheView<K, T>> getCacheView) {
      this.getCacheView = getCacheView;
    }
  }

  public static final CacheType<BuildTarget, TargetNodeMaybeIncompatible> TARGET_NODE_CACHE_TYPE =
      new CacheType<>(state -> state.targetNodeCache);
  public static final CacheType<UnconfiguredBuildTarget, UnconfiguredTargetNode>
      RAW_TARGET_NODE_CACHE_TYPE = new CacheType<>(state -> state.rawTargetNodeCache);

  /**
   * Retrieve the cache view for caching a particular type.
   *
   * <p>Note that the output type is not constrained to the type of the Class object to allow for
   * types with generics. Care should be taken to ensure that the correct class object is passed in.
   */
  public <K, T> PipelineNodeCache.Cache<K, T> getOrCreateNodeCache(CacheType<K, T> cacheType) {
    return cacheType.getCacheView.apply(this);
  }

  public PipelineNodeCache.Cache<AbsPath, BuildFileManifest> getRawNodeCache() {
    return rawNodeCache;
  }

  public PipelineNodeCache.Cache<AbsPath, PackageFileManifest> getPackageFileCache() {
    return packageFileCache;
  }

  @VisibleForTesting
  PipelineNodeCache.Cache<BuildTarget, TargetNodeMaybeIncompatible> getTargetNodeCache() {
    return targetNodeCache;
  }

  @Nullable
  private DaemonicCellState getCellState(Cell cell) {
    try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
      return cellToDaemonicState.get(cell.getCanonicalName());
    }
  }

  private DaemonicCellState getOrCreateCellState(Cell cell) {
    try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
      return cellToDaemonicState.computeIfAbsent(
          cell.getCanonicalName(), r -> new DaemonicCellState(cell, locks));
    }
  }

  @Subscribe
  public void invalidateBasedOn(WatchmanWatcherOneBigEvent event) {
    if (!event.getOverflowEvents().isEmpty()) {
      invalidateBasedOn(event.getOverflowEvents());
    } else {
      for (WatchmanPathEvent pathEvent : event.getPathEvents()) {
        invalidateBasedOn(pathEvent);
      }
    }
  }

  @VisibleForTesting
  void invalidateBasedOn(ImmutableList<WatchmanOverflowEvent> events) {
    Preconditions.checkArgument(!events.isEmpty(), "overflow event list must not be empty");

    // Non-path change event, likely an overflow due to many change events: invalidate everything.
    LOG.debug("Received non-path change event %s, assuming overflow and checking caches.", events);

    if (invalidateAllCaches()) {
      LOG.warn("Invalidated cache on watch event %s.", events);
      counters.recordCacheInvalidatedByWatchOverflow();
    }
  }

  @VisibleForTesting
  void invalidateBasedOn(WatchmanPathEvent event) {
    LOG.verbose("Parser watched event %s %s", event.getKind(), event.getPath());

    counters.recordFilesChanged();

    ForwardRelPath path = event.getPath();
    AbsPath fullPath = event.getCellPath().resolve(event.getPath());

    // We only care about creation and deletion events because modified should result in a
    // rule key change.  For parsing, these are the only events we need to care about.
    if (isPathCreateOrDeleteEvent(event)) {
      try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
        for (DaemonicCellState state : cellToDaemonicState.values()) {
          try {
            Cell cell = state.getCell();
            BuildFileTree buildFiles = buildFileTrees.get(cell);

            if (fullPath.endsWith(cell.getBuckConfigView(ParserConfig.class).getBuildFileName())) {
              LOG.debug(
                  "Build file %s changed, invalidating build file tree for cell %s",
                  fullPath, cell);
              // If a build file has been added or removed, reconstruct the build file tree.
              buildFileTrees.invalidate(cell);
            }

            // Added or removed files can affect globs, so invalidate the package build file
            // "containing" {@code path} unless its filename matches a temp file pattern.
            if (!cell.getFilesystem().isIgnored(path)) {
              invalidateContainingBuildFile(state, cell, buildFiles, path);
            } else {
              LOG.debug(
                  "Not invalidating the owning build file of %s because it is a temporary file.",
                  fullPath);
            }
          } catch (ExecutionException | UncheckedExecutionException e) {
            try {
              Throwables.throwIfInstanceOf(e, BuildFileParseException.class);
              Throwables.throwIfUnchecked(e);
              throw new RuntimeException(e);
            } catch (BuildFileParseException bfpe) {
              LOG.warn("Unable to parse already parsed build file.", bfpe);
            }
          }
        }
      }
    }

    if (configurationBuildFiles.contains(fullPath) || configurationRulesDependOn(path)) {
      invalidateAllCaches();
    } else {
      invalidatePath(fullPath);
    }
  }

  /**
   * Check whether at least one build file in {@link #configurationBuildFiles} depends on the given
   * file.
   */
  private boolean configurationRulesDependOn(ForwardRelPath path) {
    try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
      for (DaemonicCellState state : cellToDaemonicState.values()) {
        if (state.pathDependentPresentIn(path, configurationBuildFiles)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Invalidate everything which depend on path. */
  public void invalidatePath(AbsPath path) {

    // The paths from watchman are not absolute. Because of this, we adopt a conservative approach
    // to invalidating the caches.
    try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
      for (DaemonicCellState state : cellToDaemonicState.values()) {
        invalidatePath(state, path);
      }
    }
  }

  /**
   * Finds the build file responsible for the given {@link Path} and invalidates all of the cached
   * rules dependent on it.
   *
   * @param path A {@link Path}, relative to the project root and "contained" within the build file
   *     to find and invalidate.
   */
  private void invalidateContainingBuildFile(
      DaemonicCellState state, Cell cell, BuildFileTree buildFiles, ForwardRelPath path) {
    LOG.verbose("Invalidating rules dependent on change to %s in cell %s", path, cell);
    Set<ForwardRelPath> packageBuildFiles = new HashSet<>();

    // Find the closest ancestor package for the input path.  We'll definitely need to invalidate
    // that.
    Optional<ForwardRelPath> packageBuildFile = buildFiles.getBasePathOfAncestorTarget(path);
    if (packageBuildFile.isPresent()) {
      packageBuildFiles.add(packageBuildFile.get());
    }

    // If we're *not* enforcing package boundary checks, it's possible for multiple ancestor
    // packages to reference the same file
    if (cell.getBuckConfigView(ParserConfig.class).getPackageBoundaryEnforcementPolicy(path)
        != ParserConfig.PackageBoundaryEnforcement.ENFORCE) {
      while (packageBuildFile.isPresent() && packageBuildFile.get().getParent() != null) {
        packageBuildFile =
            buildFiles.getBasePathOfAncestorTarget(packageBuildFile.get().getParent());
        if (packageBuildFile.isPresent()) {
          packageBuildFiles.add(packageBuildFile.get());
        }
      }
    }

    if (packageBuildFiles.isEmpty()) {
      LOG.debug("%s is not owned by any build file.  Not invalidating anything.", path);
      return;
    }

    counters.recordBuildFilesInvalidatedByFileAddOrRemove(packageBuildFiles.size());
    counters.recordPathsAddedOrRemovedInvalidatingBuildFiles(path.toString());

    // Invalidate all the packages we found.
    for (ForwardRelPath buildFile : packageBuildFiles) {
      ForwardRelPath withBuildFile =
          buildFile.resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName());
      invalidatePath(state, cell.getRoot().resolve(withBuildFile));
    }
  }

  /**
   * Remove the targets and rules defined by {@code path} from the cache and recursively remove the
   * targets and rules defined by files that transitively include {@code path} from the cache.
   *
   * @param path The File that has changed.
   */
  private void invalidatePath(DaemonicCellState state, AbsPath path) {
    LOG.verbose("Invalidating path %s for cell %s", path, state.getCellRoot());

    int invalidatedNodes = state.invalidatePath(path, true);
    counters.recordRulesInvalidatedByWatchEvents(invalidatedNodes);
  }

  public static boolean isPathCreateOrDeleteEvent(WatchmanPathEvent event) {
    return event.getKind() == Kind.CREATE || event.getKind() == Kind.DELETE;
  }

  private boolean invalidateIfProjectBuildFileParserStateChanged(Cell cell) {
    ImmutableList<String> defaultIncludes =
        cell.getBuckConfig().getView(ParserConfig.class).getDefaultIncludes();

    ImmutableList<String> expected;
    try (AutoCloseableLocked readLock = cachedStateLock.lockRead()) {
      expected = defaultIncludesByCellName.get(cell.getCanonicalName());

      if (expected != null && defaultIncludes.equals(expected)) {
        return false;
      }

      // Someone's changed the default includes. That's almost definitely caused all our lovingly
      // cached data to be enormously wonky.
    }

    try (AutoCloseableLocked writeLock = cachedStateLock.lockWrite()) {
      defaultIncludesByCellName.put(cell.getCanonicalName(), defaultIncludes);
    }
    if (invalidateCellCaches(cell)) {
      LOG.warn(
          "Invalidating cache on default includes change (%s != %s)", expected, defaultIncludes);
      counters.recordCacheInvalidatedByDefaultIncludesChange();
    }
    return true;
  }

  private boolean invalidateCellCaches(Cell cell) {
    LOG.debug("Starting to invalidate caches for %s..", cell.getRoot());
    try (AutoCloseableLocked writeLock = cellStateLock.lockWrite()) {
      DaemonicCellState invalidated = cellToDaemonicState.remove(cell.getCanonicalName());
      if (invalidated != null) {
        LOG.debug("Cell cache data invalidated.");
      } else {
        LOG.debug("Cell caches were empty, no data invalidated.");
      }

      return invalidated != null;
    }
  }

  public boolean invalidateAllCaches() {
    LOG.debug("Starting to invalidate all caches..");
    try (AutoCloseableLocked writeLock = cellStateLock.lockWrite()) {
      boolean invalidated = !cellToDaemonicState.isEmpty();
      cellToDaemonicState.clear();
      buildFileTrees.invalidateAll();
      configurationBuildFiles.clear();
      if (invalidated) {
        LOG.debug("Cache data invalidated.");
      } else {
        LOG.debug("Caches were empty, no data invalidated.");
      }
      return invalidated;
    }
  }

  public ImmutableList<Counter> getCounters() {
    return counters.get();
  }

  @Override
  public String toString() {
    try (AutoCloseableLocked readLock = cellStateLock.lockRead()) {
      return String.format("memoized=%s", cellToDaemonicState);
    }
  }
}
