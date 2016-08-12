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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.WatchEvents;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.parser.PipelineNodeCache.Cache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteUpdateLock;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Persistent parsing data, that can exist between invocations of the {@link Parser}. All public
 * methods that cause build files to be read must be guarded by calls to
 * {@link #invalidateIfProjectBuildFileParserStateChanged(Cell)} in order to ensure that state is maintained correctly.
 */
@ThreadSafe
class DaemonicParserState {
  private static final Logger LOG = Logger.get(DaemonicParserState.class);

  /**
   * Key of the meta-rule that lists the build files executed while reading rules.
   * The value is a list of strings with the root build file as the head and included
   * build files as the tail, for example: {"__includes":["/foo/BUCK", "/foo/buck_includes"]}
   */
  private static final String INCLUDES_META_RULE = "__includes";
  private static final String CONFIGS_META_RULE = "__configs";
  private static final String ENV_META_RULE = "__env";

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

  /**
   * Taken from {@link ConcurrentMap}.
   */
  static final int DEFAULT_INITIAL_CAPACITY = 16;
  static final float DEFAULT_LOAD_FACTOR = 0.75f;
  static final int DEFAULT_TYPE_CACHE_COUNT = 4;

  private class DaemonicCache<T> implements Cache<BuildTarget, T> {

    private final Class<T> type;

    private DaemonicCache(Class<T> type) {
      this.type = type;
    }

    @Override
    public Optional<T> lookupComputedNode(Cell cell, BuildTarget target)
        throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);
      final Path buildFile = cell.getAbsolutePathToBuildFile(target);
      invalidateIfBuckConfigHasChanged(cell, buildFile);

      Cache<BuildTarget, T> state = getCache(cell);
      if (state == null) {
        return Optional.absent();
      }
      return state.lookupComputedNode(cell, target);
    }

    @Override
    public T putComputedNodeIfNotPresent(Cell cell, BuildTarget target, T targetNode)
        throws BuildTargetException {
      invalidateIfProjectBuildFileParserStateChanged(cell);
      final Path buildFile = cell.getAbsolutePathToBuildFile(target);
      invalidateIfBuckConfigHasChanged(cell, buildFile);

      return getOrCreateCache(cell).putComputedNodeIfNotPresent(cell, target, targetNode);
    }

    private @Nullable Cache<BuildTarget, T> getCache(Cell cell) {
      DaemonicCellState cellState = getCellState(cell);
      if (cellState == null) {
        return null;
      }
      return cellState.getCache(type);
    }

    private Cache<BuildTarget, T> getOrCreateCache(Cell cell) {
      return getOrCreateCellState(cell).getOrCreateCache(type);
    }
  }

  private class DaemonicRawCache implements Cache<Path, ImmutableSet<Map<String, Object>>> {

    @Override
    public Optional<ImmutableSet<Map<String, Object>>> lookupComputedNode(
        Cell cell,
        Path buildFile)
        throws BuildTargetException {
      Preconditions.checkState(buildFile.isAbsolute());
      invalidateIfProjectBuildFileParserStateChanged(cell);
      invalidateIfBuckConfigHasChanged(cell, buildFile);

      DaemonicCellState state = getCellState(cell);
      if (state == null) {
        return Optional.absent();
      }
      return state.lookupRawNodes(buildFile);
    }

    /**
     * Insert item into the cache if it was not already there. The cache will also strip any
     * meta entries from the raw nodes (these are intended for the cache as they contain information
     * about what other files to invalidate entries on).
     *
     * @param cell cell
     * @param buildFile build file
     * @param rawNodes nodes to insert
     * @return previous nodes for the file if the cache contained it, new ones otherwise.
     */
    @SuppressWarnings({"unchecked", "PMD.EmptyIfStmt"})
    @Override
    public ImmutableSet<Map<String, Object>> putComputedNodeIfNotPresent(
        Cell cell,
        Path buildFile,
        ImmutableSet<Map<String, Object>> rawNodes)
        throws BuildTargetException {
      Preconditions.checkState(buildFile.isAbsolute());
      // Technically this leads to inconsistent state if the state change happens after rawNodes
      // were computed, but before we reach the synchronized section here, however that's a problem
      // we already have, as we don't invalidate any nodes that have been retrieved from the cache
      // (and so the partially-constructed graph will contain stale nodes if the cache was
      // invalidated mid-way through the parse).
      invalidateIfProjectBuildFileParserStateChanged(cell);

      final ImmutableSet.Builder<Map<String, Object>> withoutMetaIncludesBuilder =
          ImmutableSet.builder();
      ImmutableSet.Builder<Path> dependentsOfEveryNode = ImmutableSet.builder();
      ImmutableMap<String, ImmutableMap<String, Optional<String>>> configs =
          ImmutableMap.of();
      for (Map<String, Object> rawNode : rawNodes) {
        if (rawNode.containsKey(INCLUDES_META_RULE)) {
          for (String path :
              Preconditions.checkNotNull((List<String>) rawNode.get(INCLUDES_META_RULE))) {
            dependentsOfEveryNode.add(cell.getFilesystem().resolve(path));
          }
        } else if (rawNode.containsKey(CONFIGS_META_RULE)) {
          ImmutableMap.Builder<String, ImmutableMap<String, Optional<String>>> builder =
              ImmutableMap.builder();
          Map<String, Map<String, String>> configsMeta =
              Preconditions.checkNotNull(
                  (Map<String, Map<String, String>>) rawNode.get(CONFIGS_META_RULE));
          for (Map.Entry<String, Map<String, String>> ent : configsMeta.entrySet()) {
            builder.put(
                ent.getKey(),
                ImmutableMap.copyOf(
                    Maps.transformValues(
                        ent.getValue(),
                        new Function<String, Optional<String>>() {
                          @Override
                          public Optional<String> apply(@Nullable String input) {
                            return Optional.fromNullable(input);
                          }
                        })));
          }
          configs = builder.build();
        } else if (rawNode.containsKey(ENV_META_RULE)) {
          // Skip the env meta rule for now.
        } else {
          withoutMetaIncludesBuilder.add(rawNode);
        }
      }
      final ImmutableSet<Map<String, Object>> withoutMetaIncludes =
          withoutMetaIncludesBuilder.build();

      // We also know that the rules all depend on the default includes for the
      // cell.
      BuckConfig buckConfig = cell.getBuckConfig();
      Iterable<String> defaultIncludes = new ParserConfig(buckConfig).getDefaultIncludes();
      for (String include : defaultIncludes) {
        // Default includes are given as "//path/to/file". They look like targets
        // but they are not. However, I bet someone will try and treat it like a
        // target, so find the owning cell if necessary, and then fully resolve
        // the path against the owning cell's root.
        int slashesIndex = include.indexOf("//");
        Preconditions.checkState(slashesIndex != -1);
        dependentsOfEveryNode.add(cell.getFilesystem().resolve(include.substring(2)));
      }

      return getOrCreateCellState(cell).putRawNodesIfNotPresentAndStripMetaEntries(
          buildFile,
          withoutMetaIncludes,
          dependentsOfEveryNode.build(),
          configs);
    }
  }


  private final TypeCoercerFactory typeCoercerFactory;
  private final TagSetCounter cacheInvalidatedByEnvironmentVariableChangeCounter;
  private final IntegerCounter cacheInvalidatedByDefaultIncludesChangeCounter;
  private final IntegerCounter cacheInvalidatedByWatchOverflowCounter;
  private final IntegerCounter buildFilesInvalidatedByFileAddOrRemoveCounter;
  private final IntegerCounter filesChangedCounter;
  private final IntegerCounter rulesInvalidatedByWatchEventsCounter;
  private final TagSetCounter pathsAddedOrRemovedInvalidatingBuildFiles;

  /**
   * The set of {@link Cell} instances that have been seen by this state. This information is used
   * for cache invalidation. Please see {@link #invalidateBasedOn(WatchEvent)} for example usage.
   */
  @GuardedBy("cellStateLock")
  private final ConcurrentMap<Path, DaemonicCellState> cellPathToDaemonicState;

  @GuardedBy("cellStateLock")
  private final ConcurrentMap<Class<?>, DaemonicCache<?>> typedNodeCaches;
  private final DaemonicRawCache rawNodeCache;

  private final int parsingThreads;

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * Environment used by build files. If the environment is changed, then build files need to be
   * reevaluated with the new environment, so the environment used when populating the rule cache
   * is stored between requests to parse build files and the cache is invalidated and build files
   * reevaluated if the environment changes.
   */
  @GuardedBy("cachedStateLock")
  private ImmutableMap<String, String> cachedEnvironment;

  /**
   * The default includes used by the previous run of the parser in each cell (the key is the
   * cell's root path). If this value changes, then we need to invalidate all the caches.
   */
  @GuardedBy("cachedStateLock")
  private Map<Path, Iterable<String>> cachedIncludes;

  private final AutoCloseableReadWriteUpdateLock cachedStateLock;
  private final AutoCloseableReadWriteUpdateLock cellStateLock;

  private BroadcastEventListener broadcastEventListener;

  public DaemonicParserState(
      BroadcastEventListener broadcastEventListener,
      TypeCoercerFactory typeCoercerFactory,
      int parsingThreads) {
    this.parsingThreads = parsingThreads;
    this.typeCoercerFactory = typeCoercerFactory;
    this.cacheInvalidatedByEnvironmentVariableChangeCounter = new TagSetCounter(
        COUNTER_CATEGORY,
        INVALIDATED_BY_ENV_VARS_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.cacheInvalidatedByDefaultIncludesChangeCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        INVALIDATED_BY_DEFAULT_INCLUDES_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.cacheInvalidatedByWatchOverflowCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        INVALIDATED_BY_WATCH_OVERFLOW_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.buildFilesInvalidatedByFileAddOrRemoveCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        BUILD_FILES_INVALIDATED_BY_FILE_ADD_OR_REMOVE_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.filesChangedCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        FILES_CHANGED_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.rulesInvalidatedByWatchEventsCounter = new IntegerCounter(
        COUNTER_CATEGORY,
        RULES_INVALIDATED_BY_WATCH_EVENTS_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.pathsAddedOrRemovedInvalidatingBuildFiles =
        new TagSetCounter(
            COUNTER_CATEGORY,
            PATHS_ADDED_OR_REMOVED_INVALIDATING_BUILD_FILES,
            ImmutableMap.<String, String>of());
    this.buildFileTrees = CacheBuilder.newBuilder().build(
        new CacheLoader<Cell, BuildFileTree>() {
          @Override
          public BuildFileTree load(Cell cell) throws Exception {
            return new FilesystemBackedBuildFileTree(cell.getFilesystem(), cell.getBuildFileName());
          }
        });
    this.cachedEnvironment = ImmutableMap.of();
    this.cachedIncludes = new ConcurrentHashMap<>();
    this.cellPathToDaemonicState =
        new ConcurrentHashMap<>(
            DEFAULT_INITIAL_CAPACITY,
            DEFAULT_LOAD_FACTOR,
            parsingThreads);

    this.rawNodeCache = new DaemonicRawCache();
    this.typedNodeCaches =
        new ConcurrentHashMap<>(
            DEFAULT_TYPE_CACHE_COUNT,
            DEFAULT_LOAD_FACTOR,
            parsingThreads);

    this.cachedStateLock = new AutoCloseableReadWriteUpdateLock();
    this.cellStateLock = new AutoCloseableReadWriteUpdateLock();
    this.broadcastEventListener = broadcastEventListener;
  }

  TypeCoercerFactory getTypeCoercerFactory() {
    return typeCoercerFactory;
  }

  LoadingCache<Cell, BuildFileTree> getBuildFileTrees() {
    return buildFileTrees;
  }

  @SuppressWarnings("unchecked")
  public <T> Cache<BuildTarget, T> getOrCreateNodeCache(Class<?> cacheType) {
    try (AutoCloseableLock writeLock = cellStateLock.writeLock()) {
      DaemonicCache<?> cache = typedNodeCaches.get(cacheType);
      if (cache == null) {
        cache = new DaemonicCache<>(cacheType);
        typedNodeCaches.put(cacheType, cache);
      }
      return (Cache<BuildTarget, T>) cache;
    }
  }

  public Cache<Path, ImmutableSet<Map<String, Object>>> getRawNodeCache() {
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

  public void invalidateBasedOn(WatchEvent<?> event) throws InterruptedException {
    if (!WatchEvents.isPathChangeEvent(event)) {
      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      LOG.debug("Received non-path change event %s, assuming overflow and checking caches.", event);

      if (invalidateAllCaches()) {
        LOG.warn("Invalidated cache on watch event %s.", event);
        cacheInvalidatedByWatchOverflowCounter.inc();
      }
      return;
    }

    filesChangedCounter.inc();

    Path path = (Path) event.context();

    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      for (DaemonicCellState state : cellPathToDaemonicState.values()) {
        try {
          // We only care about creation and deletion events because modified should result in a
          // rule key change.  For parsing, these are the only events we need to care about.
          if (isPathCreateOrDeleteEvent(event)) {
            Cell cell = state.getCell();
            BuildFileTree buildFiles = buildFileTrees.get(cell);

            if (path.endsWith(cell.getBuildFileName())) {
              LOG.debug(
                  "Build file %s changed, invalidating build file tree for cell %s",
                  path,
                  cell);
              // If a build file has been added or removed, reconstruct the build file tree.
              buildFileTrees.invalidate(cell);
            }

            // Added or removed files can affect globs, so invalidate the package build file
            // "containing" {@code path} unless its filename matches a temp file pattern.
            if (!isTempFile(cell, path)) {
              invalidateContainingBuildFile(cell, buildFiles, path);
            } else {
              LOG.debug(
                  "Not invalidating the owning build file of %s because it is a temporary file.",
                  state.getCellRoot().resolve(path).toAbsolutePath().toString());
            }
          }
        } catch (ExecutionException | UncheckedExecutionException e) {
          try {
            Throwables.propagateIfInstanceOf(e, BuildFileParseException.class);
            Throwables.propagate(e);
          } catch (BuildFileParseException bfpe) {
            LOG.warn("Unable to parse already parsed build file.", bfpe);
          }
        }
      }
    }

    invalidatePath(path);
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
   * Finds the build file responsible for the given {@link Path} and invalidates
   * all of the cached rules dependent on it.
   * @param path A {@link Path}, relative to the project root and "contained"
   *             within the build file to find and invalidate.
   */
  private synchronized void invalidateContainingBuildFile(
      Cell cell,
      BuildFileTree buildFiles,
      Path path) {
    LOG.debug("Invalidating rules dependent on change to %s in cell %s", path, cell);
    Set<Path> packageBuildFiles = new HashSet<>();

    // Find the closest ancestor package for the input path.  We'll definitely need to invalidate
    // that.
    Optional<Path> packageBuildFile = buildFiles.getBasePathOfAncestorTarget(path);
    packageBuildFiles.addAll(
        packageBuildFile.transform(cell.getFilesystem().getAbsolutifier()).asSet());

    // If we're *not* enforcing package boundary checks, it's possible for multiple ancestor
    // packages to reference the same file
    if (!cell.isEnforcingBuckPackageBoundaries()) {
      while (packageBuildFile.isPresent() && packageBuildFile.get().getParent() != null) {
        packageBuildFile =
            buildFiles.getBasePathOfAncestorTarget(packageBuildFile.get().getParent());
        packageBuildFiles.addAll(packageBuildFile.asSet());
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

    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      DaemonicCellState state = cellPathToDaemonicState.get(cell.getRoot());
      // Invalidate all the packages we found.
      for (Path buildFile : packageBuildFiles) {
        invalidatePath(state, buildFile.resolve(cell.getBuildFileName()));
      }
    }
  }

  /**
   * Remove the targets and rules defined by {@code path} from the cache and recursively remove
   * the targets and rules defined by files that transitively include {@code path} from the cache.
   * @param path The File that has changed.
   */
  private void invalidatePath(DaemonicCellState state, Path path) {
    LOG.debug("Invalidating path %s for cell %s", path, state.getCellRoot());

    // Paths from Watchman are not absolute.
    path = state.getCellRoot().resolve(path);
    int invalidatedNodes = state.invalidatePath(path);
    rulesInvalidatedByWatchEventsCounter.inc(invalidatedNodes);
  }

  public static boolean isPathCreateOrDeleteEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  /**
   * @param path The {@link Path} to test.
   * @return true if {@code path} is a temporary or backup file.
   */
  private boolean isTempFile(Cell cell, Path path) {
    final String fileName = path.getFileName().toString();
    Predicate<Pattern> patternMatches = new Predicate<Pattern>() {
      @Override
      public boolean apply(Pattern pattern) {
        return pattern.matcher(fileName).matches();
      }
    };
    return Iterators.any(cell.getTempFilePatterns().iterator(), patternMatches);
  }

  private synchronized void invalidateIfBuckConfigHasChanged(Cell cell, Path buildFile) {
    try (AutoCloseableLock readLock = cellStateLock.readLock()) {
      DaemonicCellState state = cellPathToDaemonicState.get(cell.getRoot());
      if (state == null) {
        return;
      }
      // Invalidates and also keeps the state cell up-to-date
      state.invalidateIfBuckConfigHasChanged(cell, buildFile);
    }
  }

  private void invalidateIfProjectBuildFileParserStateChanged(Cell cell) {
    ImmutableMap<String, String> cellEnv = cell.getBuckConfig().getFilteredEnvironment();
    Iterable<String> defaultIncludes = new ParserConfig(cell.getBuckConfig()).getDefaultIncludes();

    boolean invalidatedByEnvironmentVariableChange = false;
    boolean invalidatedByDefaultIncludesChange = false;
    Iterable<String> expected;
    ImmutableMap<String, String> originalCachedEnvironment;
    try (AutoCloseableLock updateLock = cachedStateLock.updateLock()) {
      originalCachedEnvironment = cachedEnvironment;
      expected = cachedIncludes.get(cell.getRoot());

      if (!cellEnv.equals(cachedEnvironment)) {
        // Contents of System.getenv() have changed. Cowardly refuse to accept we'll parse
        // everything the same way.
        invalidatedByEnvironmentVariableChange = true;
      } else if (expected == null || !Iterables.elementsEqual(defaultIncludes, expected)) {
        // Someone's changed the default includes. That's almost definitely caused all our lovingly
        // cached data to be enormously wonky.
        invalidatedByDefaultIncludesChange = true;
      }

      if (!invalidatedByEnvironmentVariableChange && !invalidatedByDefaultIncludesChange) {
        return;
      }

      try (AutoCloseableLock writeLock = cachedStateLock.writeLock()) {
        cachedEnvironment = cellEnv;
        cachedIncludes.put(cell.getRoot(), defaultIncludes);
      }
    }
    synchronized (this) {
      if ((invalidatedByEnvironmentVariableChange && invalidateAllCaches()) ||
          invalidateCellCaches(cell)) {
        if (invalidatedByEnvironmentVariableChange) {
          MapDifference<String, String> diff = Maps.difference(originalCachedEnvironment, cellEnv);
          LOG.warn("Invalidating cache on environment change (%s)", diff);
          Set<String> environmentChanges = new HashSet<>();
          environmentChanges.addAll(diff.entriesOnlyOnLeft().keySet());
          environmentChanges.addAll(diff.entriesOnlyOnRight().keySet());
          environmentChanges.addAll(diff.entriesDiffering().keySet());
          cacheInvalidatedByEnvironmentVariableChangeCounter.addAll(environmentChanges);
          broadcastEventListener.broadcast(ParsingEvent.environmentalChange(
              environmentChanges.toString()));
        }
        if (invalidatedByDefaultIncludesChange) {
          LOG.warn(
              "Invalidating cache on default includes change (%s != %s)",
              expected,
              defaultIncludes);
          cacheInvalidatedByDefaultIncludesChangeCounter.inc();
        }
      }
    }
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
    return String.format(
        "memoized=%s",
        cellPathToDaemonicState);
  }
}
