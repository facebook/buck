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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.counters.Counter;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.WatchEvents;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshalException;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.VisibilityPattern;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.AutoCloseableLock;
import com.facebook.buck.util.concurrent.AutoCloseableReadWriteUpdateLock;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
class DaemonicParserState implements ParsePipeline.Cache {
  private static final Logger LOG = Logger.get(DaemonicParserState.class);

  /**
   * Key of the meta-rule that lists the build files executed while reading rules.
   * The value is a list of strings with the root build file as the head and included
   * build files as the tail, for example: {"__includes":["/foo/BUCK", "/foo/buck_includes"]}
   */
  private static final String INCLUDES_META_RULE = "__includes";
  private static final String CONFIGS_META_RULE = "__configs";

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

  private final TypeCoercerFactory typeCoercerFactory;
  private final TagSetCounter cacheInvalidatedByEnvironmentVariableChangeCounter;
  private final IntegerCounter cacheInvalidatedByDefaultIncludesChangeCounter;
  private final IntegerCounter cacheInvalidatedByWatchOverflowCounter;
  private final IntegerCounter buildFilesInvalidatedByFileAddOrRemoveCounter;
  private final IntegerCounter filesChangedCounter;
  private final IntegerCounter rulesInvalidatedByWatchEventsCounter;
  @GuardedBy("nodesAndTargetsLock")
  private final ConcurrentMapCache<Path, ImmutableList<Map<String, Object>>> allRawNodes;
  @GuardedBy("nodesAndTargetsLock")
  private final HashMultimap<UnflavoredBuildTarget, BuildTarget> targetsCornucopia;
  @GuardedBy("nodesAndTargetsLock")
  private final ConcurrentMapCache<BuildTarget, TargetNode<?>> allTargetNodes;
  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * A map from absolute included files ({@code /foo/BUILD_DEFS}, for example) to the build files
   * that depend on them (typically {@code /foo/BUCK} files).
   */
  @GuardedBy("this")
  private final SetMultimap<Path, Path> buildFileDependents;

  @GuardedBy("this")
  private final Map<Path, ImmutableMap<String, ImmutableMap<String, Optional<String>>>>
      buildFileConfigs;

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

  /**
   * The set of {@link Cell} instances that have been seen by this state. This information is used
   * for cache invalidation. Please see {@link #invalidateBasedOn(WatchEvent)} for example usage.
   */
  private final Set<Cell> knownCells;

  private final AutoCloseableReadWriteUpdateLock cachedStateLock;
  private final AutoCloseableReadWriteUpdateLock nodesAndTargetsLock;

  public DaemonicParserState(
      TypeCoercerFactory typeCoercerFactory,
      int parsingThreads) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.cacheInvalidatedByEnvironmentVariableChangeCounter = new TagSetCounter(
        COUNTER_CATEGORY,
        INVALIDATED_BY_ENV_VARS_COUNTER_NAME,
        ImmutableMap.<String, String>of());
    this.allRawNodes = new ConcurrentMapCache<>(parsingThreads);
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
    this.targetsCornucopia = HashMultimap.create();
    this.allTargetNodes = new ConcurrentMapCache<>(parsingThreads);
    this.buildFileTrees = CacheBuilder.newBuilder().build(
        new CacheLoader<Cell, BuildFileTree>() {
          @Override
          public BuildFileTree load(Cell cell) throws Exception {
            return new FilesystemBackedBuildFileTree(cell.getFilesystem(), cell.getBuildFileName());
          }
        });
    this.buildFileDependents = HashMultimap.create();
    this.buildFileConfigs = new HashMap<>();
    this.cachedEnvironment = ImmutableMap.of();
    this.cachedIncludes = new ConcurrentHashMap<>();
    this.knownCells = Collections.synchronizedSet(new HashSet<Cell>());

    this.cachedStateLock = new AutoCloseableReadWriteUpdateLock();
    this.nodesAndTargetsLock = new AutoCloseableReadWriteUpdateLock();
  }

  public TypeCoercerFactory getTypeCoercerFactory() {
    return typeCoercerFactory;
  }

  @Override
  public Optional<TargetNode<?>> lookupTargetNode(
      final Cell cell,
      final BuildTarget target) throws BuildTargetException {
    invalidateIfProjectBuildFileParserStateChanged(cell);
    final Path buildFile = cell.getAbsolutePathToBuildFile(target);
    invalidateIfBuckConfigHasChanged(cell, buildFile);
    try (AutoCloseableLock readLock = nodesAndTargetsLock.readLock()) {
      return Optional.<TargetNode<?>>fromNullable(allTargetNodes.getIfPresent(target));
    }
  }

  @Override
  public TargetNode<?> putTargetNodeIfNotPresent(
      final Cell cell,
      final BuildTarget target,
      TargetNode<?> targetNode) throws BuildTargetException {
    invalidateIfProjectBuildFileParserStateChanged(cell);
    final Path buildFile = cell.getAbsolutePathToBuildFile(target);
    invalidateIfBuckConfigHasChanged(cell, buildFile);
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      TargetNode<?> updatedNode = allTargetNodes.get(target, targetNode);
      if (updatedNode == targetNode) {
        targetsCornucopia.put(target.getUnflavoredBuildTarget(), target);
      }
      return updatedNode;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public ImmutableList<Map<String, Object>> putRawNodesIfNotPresentAndStripMetaEntries(
      final Cell cell,
      final Path buildFile,
      final ImmutableList<Map<String, Object>> rawNodes) {
    Preconditions.checkState(buildFile.isAbsolute());
    // Technically this leads to inconsistent state if the state change happens after rawNodes
    // were computed, but before we reach the synchronized section here, however that's a problem
    // we already have, as we don't invalidate any nodes that have been retrieved from the cache
    // (and so the partially-constructed graph will contain stale nodes if the cash was invalidated
    // mid-way through the parse).
    invalidateIfProjectBuildFileParserStateChanged(cell);

    final ImmutableList.Builder<Map<String, Object>> withoutMetaIncludesBuilder =
        ImmutableList.builder();
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
      } else {
        withoutMetaIncludesBuilder.add(rawNode);
      }
    }
    final ImmutableList<Map<String, Object>> withoutMetaIncludes =
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

    synchronized (this) {
      try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
        ImmutableList<Map<String, Object>> updated =
            allRawNodes.get(buildFile, withoutMetaIncludes);
        buildFileConfigs.put(buildFile, configs);

        if (updated == withoutMetaIncludes) {
          // We now know all the nodes. They all implicitly depend on everything in
          // the "dependentsOfEveryNode" set.
          for (Path dependent : dependentsOfEveryNode.build()) {
            buildFileDependents.put(dependent, buildFile);
          }
        }
        return updated;
      }
    }
  }

  @Override
  public Optional<ImmutableList<Map<String, Object>>> lookupRawNodes(
      final Cell cell,
      final Path buildFile) {
    Preconditions.checkState(buildFile.isAbsolute());
    invalidateIfProjectBuildFileParserStateChanged(cell);
    invalidateIfBuckConfigHasChanged(cell, buildFile);

    try (AutoCloseableLock readLock = nodesAndTargetsLock.readLock()) {
      return Optional.fromNullable(allRawNodes.getIfPresent(buildFile));
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static TargetNode<?> createTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      ConstructorArgMarshaller marshaller,
      TypeCoercerFactory typeCoercerFactory,
      TargetNodeListener nodeListener) {
    BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(cell, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    Description<?> description = cell.getDescription(buildRuleType);

    UnflavoredBuildTarget unflavoredBuildTarget = target.withoutCell().getUnflavoredBuildTarget();
    if (target.isFlavored()) {
      if (description instanceof Flavored) {
        if (!((Flavored) description).hasFlavors(
            ImmutableSet.copyOf(target.getFlavors()))) {
          throw UnexpectedFlavorException.createWithSuggestions(cell, target);
        }
      } else {
        LOG.warn(
            "Target %s (type %s) must implement the Flavored interface " +
                "before we can check if it supports flavors: %s",
            unflavoredBuildTarget,
            buildRuleType,
            target.getFlavors());
        throw new HumanReadableException(
            "Target %s (type %s) does not currently support flavors (tried %s)",
            unflavoredBuildTarget,
            buildRuleType,
            target.getFlavors());
      }
    }

    UnflavoredBuildTarget unflavoredBuildTargetFromRawData =
        ParsePipeline.parseBuildTargetFromRawRule(
            cell.getRoot(),
            rawNode,
            buildFile);
    if (!unflavoredBuildTarget.equals(unflavoredBuildTargetFromRawData)) {
      throw new IllegalStateException(
          String.format(
              "Inconsistent internal state, target from data: %s, expected: %s, raw data: %s",
              unflavoredBuildTargetFromRawData,
              unflavoredBuildTarget,
              Joiner.on(',').withKeyValueSeparator("->").join(rawNode)));
    }

    Cell targetCell = cell.getCell(target);
    BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
        targetCell.getFilesystem(),
        target,
        new FilesystemBackedBuildFileTree(
            cell.getFilesystem(),
            cell.getBuildFileName()),
        targetCell.isEnforcingBuckPackageBoundaries());
    Object constructorArg = description.createUnpopulatedConstructorArg();
    try {
      ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
      ImmutableSet.Builder<VisibilityPattern> visibilityPatterns =
          ImmutableSet.builder();
      try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
          eventBus,
          PerfEventId.of("MarshalledConstructorArg"),
          "target",
          target)) {
        marshaller.populate(
            targetCell.getCellRoots(),
            targetCell.getFilesystem(),
            factoryParams,
            constructorArg,
            declaredDeps,
            visibilityPatterns,
            rawNode);
      }
      try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
          eventBus,
          PerfEventId.of("CreatedTargetNode"),
          "target",
          target)) {
        Hasher hasher = Hashing.sha1().newHasher();
        hasher.putString(BuckVersion.getVersion(), UTF_8);
        JsonObjectHashing.hashJsonObject(hasher, rawNode);
        TargetNode<?> node = new TargetNode(
            hasher.hash(),
            description,
            constructorArg,
            typeCoercerFactory,
            factoryParams,
            declaredDeps.build(),
            visibilityPatterns.build(),
            targetCell.getCellRoots());
        nodeListener.onCreate(buildFile, node);
        return node;
      }
    } catch (
        NoSuchBuildTargetException |
            TargetNode.InvalidSourcePathInputException e) {
      throw new HumanReadableException(e);
    } catch (ConstructorArgMarshalException e) {
      throw new HumanReadableException("%s: %s", target, e.getMessage());
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }
  }

  private static BuildRuleType parseBuildRuleTypeFromRawRule(
      Cell cell,
      Map<String, Object> map) {
    String type = (String) Preconditions.checkNotNull(
        map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return cell.getBuildRuleType(type);
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

    Preconditions.checkState(
        allTargetNodes.isEmpty() || !knownCells.isEmpty(),
        "There are cached target nodes but no known cells. Cache invalidation will not work.");

    for (Cell cell : knownCells) {
      try {
        // We only care about creation and deletion events because modified should result in a rule
        // key change.  For parsing, these are the only events we need to care about.
        if (isPathCreateOrDeleteEvent(event)) {
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
                cell.getFilesystem().resolve(path).toAbsolutePath().toString());
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

    invalidatePath(path);
  }

  public void invalidatePath(Path path) {
    Preconditions.checkState(
        allTargetNodes.isEmpty() || !knownCells.isEmpty(),
        "There are cached target nodes but no known cells. Cache invalidation will not work.");

    // The paths from watchman are not absolute. Because of this, we adopt a conservative approach
    // to invalidating the caches.
    for (Cell cell : knownCells) {
      invalidatePath(cell, path);
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

    // Invalidate all the packages we found.
    for (Path buildFile : packageBuildFiles) {
      invalidatePath(cell, buildFile.resolve(cell.getBuildFileName()));
    }
  }

  /**
   * Remove the targets and rules defined by {@code path} from the cache and recursively remove
   * the targets and rules defined by files that transitively include {@code path} from the cache.
   * @param path The File that has changed.
   */
  private synchronized void invalidatePath(Cell cell, Path path) {
    LOG.debug("Invalidating path %s for cell %s", path, cell);
    // Paths from Watchman are not absolute.
    path = cell.getFilesystem().resolve(path);



    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      // If the path is a build file for the cell, nuke the targets that it owns first. We don't
      // need to check whether or not the path ends in the build file name, since we know that
      // these are the only things that get added. Which makes for an easy life.
      List<Map<String, Object>> rawNodes = allRawNodes.getIfPresent(path);
      if (rawNodes != null) {
        rulesInvalidatedByWatchEventsCounter.inc(rawNodes.size());

        // Invalidate the target nodes first
        for (Map<String, Object> rawNode : rawNodes) {
          UnflavoredBuildTarget target =
              ParsePipeline.parseBuildTargetFromRawRule(cell.getRoot(), rawNode, path);
          LOG.debug("Invalidating target for path %s: %s", path, target);
          allTargetNodes.invalidateAll(targetsCornucopia.get(target));
          targetsCornucopia.removeAll(target);
        }

        // And then the raw node itself.
        allRawNodes.invalidate(path);
      }
    }

    // We may have been given a file that other build files depend on. Iteratively remove those.
    Iterable<Path> dependents = buildFileDependents.get(path);
    LOG.debug("Invalidating dependents for path %s: %s", path, dependents);
    for (Path dependent : dependents) {
      if (dependent.equals(path)) {
        continue;
      }
      invalidatePath(cell, dependent);
    }
    buildFileDependents.removeAll(path);

    buildFileConfigs.remove(path);
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
    ImmutableMap<String, ImmutableMap<String, Optional<String>>> usedConfigs =
        buildFileConfigs.get(buildFile);
    if (usedConfigs != null) {
      for (Map.Entry<String, ImmutableMap<String, Optional<String>>> keyEnt :
          usedConfigs.entrySet()) {
        for (Map.Entry<String, Optional<String>> valueEnt : keyEnt.getValue().entrySet()) {
          Optional<String> value =
              cell.getBuckConfig().getValue(keyEnt.getKey(), valueEnt.getKey());
          if (!value.equals(valueEnt.getValue())) {
            invalidatePath(cell, buildFile);
            return;
          }
        }
      }
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
      if (invalidateAllCaches()) {
        if (invalidatedByEnvironmentVariableChange) {
          MapDifference<String, String> diff = Maps.difference(originalCachedEnvironment, cellEnv);
          LOG.warn("Invalidating cache on environment change (%s)", diff);
          Set<String> environmentChanges = new HashSet<>();
          environmentChanges.addAll(diff.entriesOnlyOnLeft().keySet());
          environmentChanges.addAll(diff.entriesOnlyOnRight().keySet());
          environmentChanges.addAll(diff.entriesDiffering().keySet());
          cacheInvalidatedByEnvironmentVariableChangeCounter.addAll(environmentChanges);
        }
        if (invalidatedByDefaultIncludesChange) {
          LOG.warn(
              "Invalidating cache on default includes change (%s != %s)",
              expected,
              defaultIncludes);
          cacheInvalidatedByDefaultIncludesChangeCounter.inc();
        }
      }
      knownCells.clear();
      knownCells.add(cell);
    }
  }

  public synchronized boolean invalidateAllCaches() {
    LOG.debug("Starting to invalidate all caches..");
    try (AutoCloseableLock writeLock = nodesAndTargetsLock.writeLock()) {
      boolean invalidated = false;
      if (!allTargetNodes.isEmpty()) {
        invalidated = true;
      }
      allTargetNodes.invalidateAll();
      if (!targetsCornucopia.isEmpty()) {
        invalidated = true;
      }
      targetsCornucopia.clear();
      if (!allRawNodes.isEmpty()) {
        invalidated = true;
      }
      allRawNodes.invalidateAll();
      if (!buildFileDependents.isEmpty()) {
        invalidated = true;
      }
      buildFileDependents.clear();
      if (!buildFileConfigs.isEmpty()) {
        invalidated = true;
      }
      buildFileConfigs.clear();

      if (invalidated) {
        LOG.debug("Cache data invalidated.");
      } else {
        LOG.debug("Caches were empty, no data invalidated.");
      }

      return invalidated;
    }
  }

  public ImmutableList<Counter> getCounters() {
    return ImmutableList.<Counter>of(
        cacheInvalidatedByEnvironmentVariableChangeCounter,
        cacheInvalidatedByDefaultIncludesChangeCounter,
        cacheInvalidatedByWatchOverflowCounter,
        buildFilesInvalidatedByFileAddOrRemoveCounter,
        filesChangedCounter,
        rulesInvalidatedByWatchEventsCounter
    );
  }

  @Override
  public String toString() {
    return String.format(
        "memoized=%s known-cells=%s",
        allTargetNodes,
        knownCells);
  }
}
