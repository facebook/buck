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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteLock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Persistent parsing data, that can exist between invocations of the {@link Parser}. All public
 * methods that cause build files to be read must be guarded by calls to {@link
 * #invalidateIfProjectBuildFileParserStateChanged(Cell)} in order to ensure that state is
 * maintained correctly.
 */
@ThreadSafe
public class DaemonicParserState {

  private static final Logger LOG = Logger.get(DaemonicParserState.class);

  private static final String COUNTER_CATEGORY = "buck_parser_state";
  private static final String INVALIDATED_BY_ENV_VARS_COUNTER_NAME = "invalidated_by_env_vars";
  private static final String INVALIDATED_BY_DEFAULT_INCLUDES_COUNTER_NAME =
      "invalidated_by_default_includes";
  private static final String INVALIDATED_BY_WATCH_OVERFLOW_COUNTER_NAME =
      "invalidated_by_watch_overflow";
  private static final String BUILD_FILES_INVALIDATED_BY_FILE_ADD_OR_REMOVE_COUNTER_NAME =
      "build_files_invalidated_by_add_or_remove";
  private static final String FILES_CHANGED_COUNTER_NAME = "files_changed";
  private static final String RULES_INVALIDATED_BY_WATCH_EVENTS_COUNTER_NAME =
      "rules_invalidated_by_watch_events";
  private static final String PATHS_ADDED_OR_REMOVED_INVALIDATING_BUILD_FILES =
      "paths_added_or_removed_invalidating_build_files";
  // pattern all implicit include paths from build file includes should match
  // this should be kept in sync with pattern used in buck.py
  private static final Pattern INCLUDE_PATH_PATTERN = Pattern.compile("^([A-Za-z0-9_]*)//(.*)$");

  /** Taken from {@link ConcurrentMap}. */
  static final int DEFAULT_INITIAL_CAPACITY = 16;

  static final float DEFAULT_LOAD_FACTOR = 0.75f;

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
      AbsPath buildFile =
          cell.getBuckConfigView(ParserConfig.class)
              .getAbsolutePathToBuildFileUnsafe(
                  cell, type.convertToUnconfiguredBuildTargetView(target));
      invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus);

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
      Preconditions.checkState(
          !invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus),
          "Unexpected invalidation due to config/env change for %s %s",
          cell.getRoot(),
          target);

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
      invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus);

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
        // Add the PACKAGE file in the build file's directory and parent directory as dependents,
        // regardless of whether they currently exist. If a PACKAGE file is added, we need to
        // invalidate all relevant nodes.
        AbsPath packageFile = PackagePipeline.getPackageFileFromBuildFile(cell, buildFile);
        ImmutableSet<AbsPath> parentPackageFiles =
            PackagePipeline.getAllParentPackageFiles(cell, packageFile);
        dependentsOfEveryNode.add(packageFile).addAll(parentPackageFiles);
      }

      return getOrCreateCellState(cell)
          .putBuildFileManifestIfNotPresent(
              buildFile,
              manifest,
              dependentsOfEveryNode.build(),
              manifest.getEnv().orElse(ImmutableMap.of()));
    }
  }

  /** Stateless view of caches on object that conforms to {@link PipelineNodeCache.Cache}. */
  private class DaemonicPackageCache
      implements PipelineNodeCache.Cache<AbsPath, PackageFileManifest> {

    @Override
    public Optional<PackageFileManifest> lookupComputedNode(
        Cell cell, AbsPath packageFile, BuckEventBus eventBus) throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);
      invalidateIfBuckConfigOrEnvHasChanged(cell, packageFile, eventBus);

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

      return getOrCreateCellState(cell)
          .putPackageFileManifestIfNotPresent(
              packageFile,
              manifest,
              packageDependents.build(),
              manifest.getEnv().orElse(ImmutableMap.of()));
    }
  }

  /** Add all the includes from the manifest and Buck defaults. */
  private static void addAllIncludes(
      ImmutableSet.Builder<AbsPath> dependents,
      ImmutableSortedSet<String> manifestIncludes,
      Cell cell) {
    manifestIncludes.forEach(
        includedPath -> dependents.add(AbsPath.of(cell.getFilesystem().resolve(includedPath))));

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
    Optional<String> cellName = Optional.ofNullable(matcher.group(1));
    String includePath = matcher.group(2);
    return AbsPath.of(
        cellPathResolver
            .getCellPath(cellName)
            .map(cellPath -> cellPath.resolve(includePath))
            .orElseGet(() -> cell.getFilesystem().resolve(includePath)));
  }

  private final TagSetCounter cacheInvalidatedByEnvironmentVariableChangeCounter;
  private final IntegerCounter cacheInvalidatedByDefaultIncludesChangeCounter;
  private final IntegerCounter cacheInvalidatedByWatchOverflowCounter;
  private final IntegerCounter buildFilesInvalidatedByFileAddOrRemoveCounter;
  private final IntegerCounter filesChangedCounter;
  private final IntegerCounter rulesInvalidatedByWatchEventsCounter;
  private final TagSetCounter pathsAddedOrRemovedInvalidatingBuildFiles;

  /**
   * The set of {@link Cell} instances that have been seen by this state. This information is used
   * for cache invalidation. Please see {@link #invalidateBasedOn(WatchmanPathEvent)} for example
   * usage.
   */
  @GuardedBy("cellStateLock")
  private final ConcurrentMap<AbsPath, DaemonicCellState> cellPathToDaemonicState;

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

  private final int parsingThreads;

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * The default includes used by the previous run of the parser in each cell (the key is the cell's
   * root path). If this value changes, then we need to invalidate all the caches.
   */
  @GuardedBy("cachedStateLock")
  private Map<AbsPath, Iterable<String>> cachedIncludes;

  private final AutoCloseableReadWriteLock cachedStateLock;
  private final AutoCloseableReadWriteLock cellStateLock;

  public DaemonicParserState(int parsingThreads) {
    this.parsingThreads = parsingThreads;
    this.cacheInvalidatedByEnvironmentVariableChangeCounter =
        new TagSetCounter(
            COUNTER_CATEGORY, INVALIDATED_BY_ENV_VARS_COUNTER_NAME, ImmutableMap.of());
    this.cacheInvalidatedByDefaultIncludesChangeCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, INVALIDATED_BY_DEFAULT_INCLUDES_COUNTER_NAME, ImmutableMap.of());
    this.cacheInvalidatedByWatchOverflowCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, INVALIDATED_BY_WATCH_OVERFLOW_COUNTER_NAME, ImmutableMap.of());
    this.buildFilesInvalidatedByFileAddOrRemoveCounter =
        new IntegerCounter(
            COUNTER_CATEGORY,
            BUILD_FILES_INVALIDATED_BY_FILE_ADD_OR_REMOVE_COUNTER_NAME,
            ImmutableMap.of());
    this.filesChangedCounter =
        new IntegerCounter(COUNTER_CATEGORY, FILES_CHANGED_COUNTER_NAME, ImmutableMap.of());
    this.rulesInvalidatedByWatchEventsCounter =
        new IntegerCounter(
            COUNTER_CATEGORY, RULES_INVALIDATED_BY_WATCH_EVENTS_COUNTER_NAME, ImmutableMap.of());
    this.pathsAddedOrRemovedInvalidatingBuildFiles =
        new TagSetCounter(
            COUNTER_CATEGORY, PATHS_ADDED_OR_REMOVED_INVALIDATING_BUILD_FILES, ImmutableMap.of());
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
    this.cachedIncludes = new ConcurrentHashMap<>();
    this.cellPathToDaemonicState =
        new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, parsingThreads);

    this.rawNodeCache = new DaemonicRawCacheView();
    this.packageFileCache = new DaemonicPackageCache();

    this.cachedStateLock = new AutoCloseableReadWriteLock();
    this.cellStateLock = new AutoCloseableReadWriteLock();
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
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      return cellPathToDaemonicState.get(cell.getRoot());
    }
  }

  private DaemonicCellState getOrCreateCellState(Cell cell) {
    try (AutoCloseableLock writeLock = cellStateLock.writeLock()) {
      DaemonicCellState state = cellPathToDaemonicState.get(cell.getRoot());
      if (state == null) {
        state = new DaemonicCellState(cell, parsingThreads);
        cellPathToDaemonicState.put(cell.getRoot(), state);
      }
      return state;
    }
  }

  @Subscribe
  public void invalidateBasedOn(WatchmanOverflowEvent event) {
    // Non-path change event, likely an overflow due to many change events: invalidate everything.
    LOG.debug("Received non-path change event %s, assuming overflow and checking caches.", event);

    if (invalidateAllCaches()) {
      LOG.warn("Invalidated cache on watch event %s.", event);
      cacheInvalidatedByWatchOverflowCounter.inc();
    }
  }

  @Subscribe
  public void invalidateBasedOn(WatchmanPathEvent event) {
    LOG.verbose("Parser watched event %s %s", event.getKind(), event.getPath());

    filesChangedCounter.inc();

    RelPath path = event.getPath();
    AbsPath fullPath = event.getCellPath().resolve(event.getPath());

    // We only care about creation and deletion events because modified should result in a
    // rule key change.  For parsing, these are the only events we need to care about.
    if (isPathCreateOrDeleteEvent(event)) {
      try (AutoCloseableLock readLock = cellStateLock.readLock()) {
        for (DaemonicCellState state : cellPathToDaemonicState.values()) {
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

    if (configurationBuildFiles.contains(fullPath) || configurationRulesDependOn(path.getPath())) {
      invalidateAllCaches();
    } else {
      invalidatePath(fullPath);
    }
  }

  /**
   * Check whether at least one build file in {@link #configurationBuildFiles} depends on the given
   * file.
   */
  private boolean configurationRulesDependOn(Path path) {
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      for (DaemonicCellState state : cellPathToDaemonicState.values()) {
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
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      for (DaemonicCellState state : cellPathToDaemonicState.values()) {
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
      DaemonicCellState state, Cell cell, BuildFileTree buildFiles, RelPath path) {
    LOG.verbose("Invalidating rules dependent on change to %s in cell %s", path, cell);
    Set<RelPath> packageBuildFiles = new HashSet<>();

    // Find the closest ancestor package for the input path.  We'll definitely need to invalidate
    // that.
    Optional<RelPath> packageBuildFile = buildFiles.getBasePathOfAncestorTarget(path);
    if (packageBuildFile.isPresent()) {
      packageBuildFiles.add(packageBuildFile.get());
    }

    // If we're *not* enforcing package boundary checks, it's possible for multiple ancestor
    // packages to reference the same file
    if (cell.getBuckConfigView(ParserConfig.class)
            .getPackageBoundaryEnforcementPolicy(path.getPath())
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

    buildFilesInvalidatedByFileAddOrRemoveCounter.inc(packageBuildFiles.size());
    pathsAddedOrRemovedInvalidatingBuildFiles.add(path.toString());

    // Invalidate all the packages we found.
    for (RelPath buildFile : packageBuildFiles) {
      invalidatePath(
          state,
          cell.getRoot()
              .resolve(
                  buildFile.resolve(
                      cell.getBuckConfigView(ParserConfig.class).getBuildFileName())));
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

    // Paths passed in may not be absolute.
    path = state.getCellRoot().resolve(path.getPath());
    int invalidatedNodes = state.invalidatePath(path);
    rulesInvalidatedByWatchEventsCounter.inc(invalidatedNodes);
  }

  public static boolean isPathCreateOrDeleteEvent(WatchmanPathEvent event) {
    return event.getKind() == Kind.CREATE || event.getKind() == Kind.DELETE;
  }

  private boolean invalidateIfBuckConfigOrEnvHasChanged(
      Cell cell, AbsPath buildFile, BuckEventBus eventBus) {
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      DaemonicCellState state = cellPathToDaemonicState.get(cell.getRoot());
      if (state == null) {
        return false;
      }

      // Keep track of any invalidations.
      boolean hasInvalidated = false;

      // Currently, if `.buckconfig` settings change, we restart the entire daemon, meaning checking
      // for `.buckconfig`-based invalidations is redundant. (see
      // {@link com.facebook.buck.cli.DaemonLifecycleManager#getDaemon} for where we restart the
      // daemon and {@link com.facebook.buck.config.BuckConfig's static initializer for the
      // whitelist of fields.

      // Invalidate based on env vars.
      Optional<MapDifference<String, String>> envDiff =
          state.invalidateIfEnvHasChanged(cell, buildFile);
      if (envDiff.isPresent()) {
        hasInvalidated = true;
        MapDifference<String, String> diff = envDiff.get();
        LOG.info("Invalidating cache on environment change (%s)", diff);
        Set<String> environmentChanges = new HashSet<>();
        environmentChanges.addAll(diff.entriesOnlyOnLeft().keySet());
        environmentChanges.addAll(diff.entriesOnlyOnRight().keySet());
        environmentChanges.addAll(diff.entriesDiffering().keySet());
        cacheInvalidatedByEnvironmentVariableChangeCounter.addAll(environmentChanges);
        eventBus.post(ParsingEvent.environmentalChange(environmentChanges.toString()));
      }

      return hasInvalidated;
    }
  }

  private boolean invalidateIfProjectBuildFileParserStateChanged(Cell cell) {
    Iterable<String> defaultIncludes =
        cell.getBuckConfig().getView(ParserConfig.class).getDefaultIncludes();

    boolean invalidatedByDefaultIncludesChange = false;
    Iterable<String> expected;
    try (AutoCloseableLock readLock = cachedStateLock.readLock()) {
      expected = cachedIncludes.get(cell.getRoot());

      if (expected == null || !Iterables.elementsEqual(defaultIncludes, expected)) {
        // Someone's changed the default includes. That's almost definitely caused all our lovingly
        // cached data to be enormously wonky.
        invalidatedByDefaultIncludesChange = true;
      }

      if (!invalidatedByDefaultIncludesChange) {
        return false;
      }
    }
    try (AutoCloseableLock writeLock = cachedStateLock.writeLock()) {
      cachedIncludes.put(cell.getRoot(), defaultIncludes);
    }
    if (invalidateCellCaches(cell)) {
      LOG.warn(
          "Invalidating cache on default includes change (%s != %s)", expected, defaultIncludes);
      cacheInvalidatedByDefaultIncludesChangeCounter.inc();
    }
    return true;
  }

  public boolean invalidateCellCaches(Cell cell) {
    LOG.debug("Starting to invalidate caches for %s..", cell.getRoot());
    try (AutoCloseableLock writeLock = cellStateLock.writeLock()) {
      boolean invalidated = cellPathToDaemonicState.containsKey(cell.getRoot());
      cellPathToDaemonicState.remove(cell.getRoot());
      if (invalidated) {
        LOG.debug("Cell cache data invalidated.");
      } else {
        LOG.debug("Cell caches were empty, no data invalidated.");
      }

      return invalidated;
    }
  }

  public boolean invalidateAllCaches() {
    LOG.debug("Starting to invalidate all caches..");
    try (AutoCloseableLock writeLock = cellStateLock.writeLock()) {
      boolean invalidated = !cellPathToDaemonicState.isEmpty();
      cellPathToDaemonicState.clear();
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
    return ImmutableList.of(
        cacheInvalidatedByEnvironmentVariableChangeCounter,
        cacheInvalidatedByDefaultIncludesChangeCounter,
        cacheInvalidatedByWatchOverflowCounter,
        buildFilesInvalidatedByFileAddOrRemoveCounter,
        filesChangedCounter,
        rulesInvalidatedByWatchEventsCounter,
        pathsAddedOrRemovedInvalidatingBuildFiles);
  }

  @Override
  public String toString() {
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      return String.format("memoized=%s", cellPathToDaemonicState);
    }
  }
}
