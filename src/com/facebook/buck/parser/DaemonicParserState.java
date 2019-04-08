/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteLock;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
  private class DaemonicCacheView<T> implements PipelineNodeCache.Cache<BuildTarget, T> {

    private final Class<T> type;

    private DaemonicCacheView(Class<T> type) {
      this.type = type;
    }

    @Override
    public Optional<T> lookupComputedNode(Cell cell, BuildTarget target, BuckEventBus eventBus)
        throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);
      Path buildFile =
          cell.getBuckConfigView(ParserConfig.class)
              .getAbsolutePathToBuildFileUnsafe(cell, target.getUnconfiguredBuildTargetView());
      invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus);

      DaemonicCellState.Cache<T> state = getCache(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupComputedNode(target);
    }

    @Override
    public T putComputedNodeIfNotPresent(
        Cell cell, BuildTarget target, T targetNode, BuckEventBus eventBus)
        throws BuildTargetException {

      // Verify we don't invalidate the build file at this point, as, at this point, we should have
      // already called `lookupComputedNode` which should have done any invalidation.
      Preconditions.checkState(
          !invalidateIfProjectBuildFileParserStateChanged(cell),
          "Unexpected invalidation due to build file parser state change for %s %s",
          cell.getRoot(),
          target);
      Path buildFile =
          cell.getBuckConfigView(ParserConfig.class)
              .getAbsolutePathToBuildFileUnsafe(cell, target.getUnconfiguredBuildTargetView());
      Preconditions.checkState(
          !invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus),
          "Unexpected invalidation due to config/env change for %s %s",
          cell.getRoot(),
          target);

      return getOrCreateCache(cell).putComputedNodeIfNotPresent(target, targetNode);
    }

    private @Nullable DaemonicCellState.Cache<T> getCache(Cell cell) {
      DaemonicCellState cellState = getCellState(cell);
      if (cellState == null) {
        return null;
      }
      return cellState.getCache(type);
    }

    private DaemonicCellState.Cache<T> getOrCreateCache(Cell cell) {
      return getOrCreateCellState(cell).getOrCreateCache(type);
    }
  }

  /** Stateless view of caches on object that conforms to {@link PipelineNodeCache.Cache}. */
  private class DaemonicRawCacheView implements PipelineNodeCache.Cache<Path, BuildFileManifest> {

    @Override
    public Optional<BuildFileManifest> lookupComputedNode(
        Cell cell, Path buildFile, BuckEventBus eventBus) throws BuildTargetException {
      Preconditions.checkState(buildFile.isAbsolute());
      invalidateIfProjectBuildFileParserStateChanged(cell);
      invalidateIfBuckConfigOrEnvHasChanged(cell, buildFile, eventBus);

      DaemonicCellState state = getCellState(cell);
      if (state == null) {
        return Optional.empty();
      }
      return state.lookupRawNodes(buildFile);
    }

    /**
     * Insert item into the cache if it was not already there. The cache will also strip any meta
     * entries from the raw nodes (these are intended for the cache as they contain information
     * about what other files to invalidate entries on).
     *
     * @param cell cell
     * @param buildFile build file
     * @return previous nodes for the file if the cache contained it, new ones otherwise.
     */
    @Override
    public BuildFileManifest putComputedNodeIfNotPresent(
        Cell cell, Path buildFile, BuildFileManifest manifest, BuckEventBus eventBus)
        throws BuildTargetException {
      Preconditions.checkState(buildFile.isAbsolute());
      // Technically this leads to inconsistent state if the state change happens after rawNodes
      // were computed, but before we reach the synchronized section here, however that's a problem
      // we already have, as we don't invalidate any nodes that have been retrieved from the cache
      // (and so the partially-constructed graph will contain stale nodes if the cache was
      // invalidated mid-way through the parse).
      invalidateIfProjectBuildFileParserStateChanged(cell);

      ImmutableSet.Builder<Path> dependentsOfEveryNode = ImmutableSet.builder();

      manifest
          .getIncludes()
          .forEach(
              includedPath ->
                  dependentsOfEveryNode.add(cell.getFilesystem().resolve(includedPath)));

      // We also know that the rules all depend on the default includes for the cell.
      BuckConfig buckConfig = cell.getBuckConfig();
      Iterable<String> defaultIncludes =
          buckConfig.getView(ParserConfig.class).getDefaultIncludes();
      for (String include : defaultIncludes) {
        dependentsOfEveryNode.add(resolveIncludePath(cell, include, cell.getCellPathResolver()));
      }

      return getOrCreateCellState(cell)
          .putRawNodesIfNotPresentAndStripMetaEntries(
              buildFile,
              manifest,
              dependentsOfEveryNode.build(),
              manifest.getEnv().orElse(ImmutableMap.of()));
    }

    /**
     * Resolves a path of an include string like {@code repo//foo/macro_defs} to a filesystem path.
     */
    private Path resolveIncludePath(Cell cell, String include, CellPathResolver cellPathResolver) {
      // Default includes are given as "cell//path/to/file". They look like targets
      // but they are not. However, I bet someone will try and treat it like a
      // target, so find the owning cell if necessary, and then fully resolve
      // the path against the owning cell's root.
      Matcher matcher = INCLUDE_PATH_PATTERN.matcher(include);
      Preconditions.checkState(matcher.matches());
      Optional<String> cellName = Optional.ofNullable(matcher.group(1));
      String includePath = matcher.group(2);
      return cellPathResolver
          .getCellPath(cellName)
          .map(cellPath -> cellPath.resolve(includePath))
          .orElseGet(() -> cell.getFilesystem().resolve(includePath));
    }
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
  private final ConcurrentMap<Path, DaemonicCellState> cellPathToDaemonicState;

  private final LoadingCache<Class<?>, DaemonicCacheView<?>> typedNodeCaches =
      CacheBuilder.newBuilder().build(CacheLoader.from(cls -> new DaemonicCacheView<>(cls)));
  private final DaemonicRawCacheView rawNodeCache;

  private final int parsingThreads;

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * The default includes used by the previous run of the parser in each cell (the key is the cell's
   * root path). If this value changes, then we need to invalidate all the caches.
   */
  @GuardedBy("cachedStateLock")
  private Map<Path, Iterable<String>> cachedIncludes;

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

    this.cachedStateLock = new AutoCloseableReadWriteLock();
    this.cellStateLock = new AutoCloseableReadWriteLock();
  }

  LoadingCache<Cell, BuildFileTree> getBuildFileTrees() {
    return buildFileTrees;
  }

  /**
   * Retrieve the cache view for caching a particular type.
   *
   * <p>Note that the output type is not constrained to the type of the Class object to allow for
   * types with generics. Care should be taken to ensure that the correct class object is passed in.
   */
  @SuppressWarnings("unchecked")
  public <T> PipelineNodeCache.Cache<BuildTarget, T> getOrCreateNodeCache(Class<?> cacheType) {
    try {
      return (PipelineNodeCache.Cache<BuildTarget, T>) typedNodeCaches.get(cacheType);
    } catch (ExecutionException e) {
      throw new IllegalStateException("typedNodeCaches CacheLoader should not throw.", e);
    }
  }

  public PipelineNodeCache.Cache<Path, BuildFileManifest> getRawNodeCache() {
    return rawNodeCache;
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

    Path path = event.getPath();
    Path fullPath = event.getCellPath().resolve(event.getPath());

    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      for (DaemonicCellState state : cellPathToDaemonicState.values()) {
        try {
          // We only care about creation and deletion events because modified should result in a
          // rule key change.  For parsing, these are the only events we need to care about.
          if (isPathCreateOrDeleteEvent(event)) {
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
              invalidateContainingBuildFile(cell, buildFiles, path);
            } else {
              LOG.debug(
                  "Not invalidating the owning build file of %s because it is a temporary file.",
                  fullPath);
            }
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

    invalidatePath(fullPath);
  }

  public void invalidatePath(Path path) {

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
  private void invalidateContainingBuildFile(Cell cell, BuildFileTree buildFiles, Path path) {
    LOG.verbose("Invalidating rules dependent on change to %s in cell %s", path, cell);
    Set<Path> packageBuildFiles = new HashSet<>();

    // Find the closest ancestor package for the input path.  We'll definitely need to invalidate
    // that.
    Optional<Path> packageBuildFile = buildFiles.getBasePathOfAncestorTarget(path);
    if (packageBuildFile.isPresent()) {
      packageBuildFiles.add(cell.getFilesystem().resolve(packageBuildFile.get()));
    }

    // If we're *not* enforcing package boundary checks, it's possible for multiple ancestor
    // packages to reference the same file
    if (!cell.getBuckConfigView(ParserConfig.class).isEnforcingBuckPackageBoundaries(path)) {
      while (packageBuildFile.isPresent() && packageBuildFile.get().getParent() != null) {
        packageBuildFile =
            buildFiles.getBasePathOfAncestorTarget(packageBuildFile.get().getParent());
        if (packageBuildFile.isPresent()) {
          packageBuildFiles.add(packageBuildFile.get());
        }
      }
    }

    if (packageBuildFiles.isEmpty()) {
      LOG.debug(
          "%s is not owned by any build file.  Not invalidating anything.",
          cell.getFilesystem().resolve(path).toAbsolutePath().toString());
      return;
    }

    buildFilesInvalidatedByFileAddOrRemoveCounter.inc(packageBuildFiles.size());
    pathsAddedOrRemovedInvalidatingBuildFiles.add(path.toString());

    DaemonicCellState state;
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      state = cellPathToDaemonicState.get(cell.getRoot());
    }
    // Invalidate all the packages we found.
    for (Path buildFile : packageBuildFiles) {
      invalidatePath(
          state, buildFile.resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
    }
  }

  /**
   * Remove the targets and rules defined by {@code path} from the cache and recursively remove the
   * targets and rules defined by files that transitively include {@code path} from the cache.
   *
   * @param path The File that has changed.
   */
  private void invalidatePath(DaemonicCellState state, Path path) {
    LOG.verbose("Invalidating path %s for cell %s", path, state.getCellRoot());

    // Paths passed in may not be absolute.
    path = state.getCellRoot().resolve(path);
    int invalidatedNodes = state.invalidatePath(path);
    rulesInvalidatedByWatchEventsCounter.inc(invalidatedNodes);
  }

  public static boolean isPathCreateOrDeleteEvent(WatchmanPathEvent event) {
    return event.getKind() == WatchmanPathEvent.Kind.CREATE
        || event.getKind() == WatchmanPathEvent.Kind.DELETE;
  }

  private boolean invalidateIfBuckConfigOrEnvHasChanged(
      Cell cell, Path buildFile, BuckEventBus eventBus) {
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
    if (invalidateCellCaches(cell) && invalidatedByDefaultIncludesChange) {
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
