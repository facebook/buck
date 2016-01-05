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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.WatchEvents;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.JsonObjectHashing;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.BuildTargetPattern;
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
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
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
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.SetMultimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

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

  private final TypeCoercerFactory typeCoercerFactory;
  private final ConstructorArgMarshaller marshaller;
  private final OptimisticLoadingCache<Path, ImmutableList<Map<String, Object>>> allRawNodes;
  @GuardedBy("this")
  private final HashMultimap<UnflavoredBuildTarget, BuildTarget> targetsCornucopia;
  private final OptimisticLoadingCache<BuildTarget, TargetNode<?>> allTargetNodes;
  private final Predicate<BuildTarget> hasCachedTargetNodeForBuildTargetPredicate;
  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  /**
   * A map from absolute included files ({@code /foo/BUILD_DEFS}, for example) to the build files
   * that depend on them (typically {@code /foo/BUCK} files).
   */
  @GuardedBy("this")
  private final SetMultimap<Path, Path> buildFileDependents;

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

  public DaemonicParserState(
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      int parsingThreads) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.marshaller = marshaller;
    this.allRawNodes = new OptimisticLoadingCache<>(parsingThreads);
    this.targetsCornucopia = HashMultimap.create();
    this.allTargetNodes = new OptimisticLoadingCache<>(parsingThreads);
    this.hasCachedTargetNodeForBuildTargetPredicate = new Predicate<BuildTarget>() {
      @Override
      public boolean apply(BuildTarget buildTarget) {
        return hasCachedTargetNodeForBuildTarget(buildTarget);
      }
    };
    this.buildFileTrees = CacheBuilder.newBuilder().build(
        new CacheLoader<Cell, BuildFileTree>() {
          @Override
          public BuildFileTree load(Cell cell) throws Exception {
            return new FilesystemBackedBuildFileTree(cell.getFilesystem(), cell.getBuildFileName());
          }
        });
    this.buildFileDependents = HashMultimap.create();
    this.cachedEnvironment = ImmutableMap.of();
    this.cachedIncludes = new ConcurrentHashMap<>();
    this.knownCells = Collections.synchronizedSet(new HashSet<Cell>());

    this.cachedStateLock = new AutoCloseableReadWriteUpdateLock();
  }

  public ImmutableList<Map<String, Object>> getAllRawNodes(
      Cell cell,
      ProjectBuildFileParser parser,
      Path buildFile) throws BuildFileParseException, InterruptedException {
    Preconditions.checkState(buildFile.isAbsolute());
    invalidateIfProjectBuildFileParserStateChanged(cell);

    try {
      return loadRawNodes(cell, buildFile, parser);
    } catch (UncheckedExecutionException | ExecutionException e) {
      throw propagate(e);
    }
  }

  public ImmutableSet<TargetNode<?>> getAllTargetNodes(
      final BuckEventBus eventBus,
      final Cell cell,
      ProjectBuildFileParser parser,
      final Path buildFile,
      final TargetNodeListener nodeListener) throws BuildFileParseException, InterruptedException {
    Preconditions.checkState(buildFile.isAbsolute());
    invalidateIfProjectBuildFileParserStateChanged(cell);
    try {
      List<Map<String, Object>> allRawNodes = loadRawNodes(cell, buildFile, parser);

      ImmutableSet.Builder<TargetNode<?>> nodes = ImmutableSet.builder();
      for (final Map<String, Object> rawNode : allRawNodes) {
        UnflavoredBuildTarget unflavored = parseBuildTargetFromRawRule(cell.getRoot(), rawNode);
        final BuildTarget target = BuildTarget.of(unflavored);

        TargetNode<?> node = allTargetNodes.get(target, new Callable<TargetNode<?>>() {
          @Override
          public TargetNode<?> call() throws Exception {
            return createTargetNode(eventBus, cell, buildFile, target, rawNode, nodeListener);
          }
        });

        nodes.add(node);
      }
      return nodes.build();
    } catch (UncheckedExecutionException | ExecutionException e) {
      throw propagate(e);
    }
  }

  public TargetNode<?> getTargetNode(
      final BuckEventBus eventBus,
      final Cell cell,
      final ProjectBuildFileParser parser,
      final BuildTarget target,
      final TargetNodeListener nodeListener
  ) throws BuildFileParseException, BuildTargetException, InterruptedException {
    invalidateIfProjectBuildFileParserStateChanged(cell);
    try {
      return allTargetNodes.get(
          target,
          new Callable<TargetNode<?>>() {
            @Override
            public TargetNode<?> call() throws Exception {
              Path buildFile = cell.getAbsolutePathToBuildFile(target);
              Preconditions.checkState(buildFile.isAbsolute());
              List<Map<String, Object>> rawNodes = loadRawNodes(cell, buildFile, parser);

              for (Map<String, Object> rawNode : rawNodes) {
                Object shortName = rawNode.get("name");

                if (target.getShortName().equals(shortName)) {
                  return createTargetNode(
                      eventBus,
                      cell,
                      buildFile,
                      target,
                      rawNode,
                      nodeListener);
                }
              }

              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(
                      target,
                      BuildTargetPatternParser.forBaseName(target.getBaseName()),
                      cell.getBuildFileName(),
                      "Defined in file: " + buildFile));
            }
          });
    } catch (UncheckedExecutionException | ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), BuildTargetException.class);
      throw propagate(e);
    }
  }

  public boolean hasCachedTargetNodeForBuildTarget(BuildTarget buildTarget) {
    return allTargetNodes.containsKey(buildTarget);
  }

  public Predicate<BuildTarget> getHasCachedTargetNodeForBuildTargetPredicate() {
    return hasCachedTargetNodeForBuildTargetPredicate;
  }

  private RuntimeException propagate(Throwable e)
      throws BuildFileParseException, InterruptedException {
    Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), BuildTargetParseException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), HumanReadableException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), InterruptedException.class);
    if (e instanceof ExecutionException | e instanceof UncheckedExecutionException) {
      Throwable cause = e.getCause();
      if (cause instanceof ExecutionException | cause instanceof UncheckedExecutionException) {
        throw propagate(cause);
      }

      if (cause != null) {
        return Throwables.propagate(cause);
      }
    }

    return Throwables.propagate(e);
  }

  private ImmutableList<Map<String, Object>> loadRawNodes(
      final Cell cell,
      final Path buildFile,
      final ProjectBuildFileParser parser) throws ExecutionException {
    return allRawNodes.get(
        buildFile,
        new Callable<ImmutableList<Map<String, Object>>>() {
          @SuppressWarnings("unchecked")
          @Override
          public ImmutableList<Map<String, Object>> call() throws Exception {
            List<Map<String, Object>> rawNodes = parser.getAllRulesAndMetaRules(buildFile);
            ImmutableSet<Path> dependentsOfEveryNode = ImmutableSet.of();
            ImmutableList.Builder<Map<String, Object>> toReturn = ImmutableList.builder();
            for (Map<String, Object> rawNode : rawNodes) {
              if (rawNode.containsKey(INCLUDES_META_RULE)) {
                // INCLUDES_META_RULE maps to a list of file paths: the head is a
                // dependent build file and the tail is a list of the files it includes.
                List<String> fileNames = ((List<String>) rawNode.get(INCLUDES_META_RULE));
                Preconditions.checkNotNull(fileNames);
                dependentsOfEveryNode = FluentIterable.from(fileNames)
                    .transform(
                        new Function<String, Path>() {
                          @Override
                          public Path apply(String path) {
                            return cell.getFilesystem().resolve(Paths.get(path));
                          }
                        })
                    .toSet();
              } else {
                toReturn.add(rawNode);
              }
            }

            synchronized (this) {
              // We now know all the nodes. They all implicitly depend on everything in
              // the "dependentsOfEveryNode" set.
              for (Path dependent : dependentsOfEveryNode) {
                buildFileDependents.put(dependent, buildFile);
              }
            }

            // We also know that the rules all depend on the default includes for the
            // cell.
            Iterable<String> defaultIncludes =
                new ParserConfig(cell.getBuckConfig()).getDefaultIncludes();
            synchronized (this) {
              for (String include : defaultIncludes) {
                // Default includes are given as "//path/to/file". They look like targets
                // but they are not. However, I bet someone will try and treat it like a
                // target, so find the owning cell if necessary, and then fully resolve
                // the path against the owning cell's root.
                int slashesIndex = include.indexOf("//");
                Preconditions.checkState(slashesIndex != -1);

                buildFileDependents.put(
                    cell.getFilesystem().resolve(include.substring(2)),
                    buildFile);
              }
            }

            return toReturn.build();
          }
        });
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private TargetNode<?> createTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawNode,
      TargetNodeListener nodeListener) {
    BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(cell, rawNode);

    // Because of the way that the parser works, we know this can never return null.
    Description<?> description = cell.getDescription(buildRuleType);

    if (target.isFlavored()) {
      if (description instanceof Flavored) {
        if (!((Flavored) description).hasFlavors(
            ImmutableSet.copyOf(target.getFlavors()))) {
          throw new HumanReadableException(
              "Unrecognized flavor in target %s while parsing %s%s.",
              target,
              UnflavoredBuildTarget.BUILD_TARGET_PREFIX,
              MorePaths.pathWithUnixSeparators(
                  target.getBasePath().resolve(cell.getBuildFileName())));
        }
      } else {
        LOG.warn(
            "Target %s (type %s) must implement the Flavored interface " +
                "before we can check if it supports flavors: %s",
            target.getUnflavoredBuildTarget(),
            buildRuleType,
            target.getFlavors());
        throw new HumanReadableException(
            "Target %s (type %s) does not currently support flavors (tried %s)",
            target.getUnflavoredBuildTarget(),
            buildRuleType,
            target.getFlavors());
      }
    }

    Cell targetCell = cell.getCell(target);
    BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
        targetCell.getFilesystem(),
        target.withoutCell(),
        new FilesystemBackedBuildFileTree(
            cell.getFilesystem(),
            cell.getBuildFileName()),
        targetCell.isEnforcingBuckPackageBoundaries());
    Object constructorArg = description.createUnpopulatedConstructorArg();
    try {
      ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
      ImmutableSet.Builder<BuildTargetPattern> visibilityPatterns =
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
        synchronized (this) {
          targetsCornucopia.put(target.getUnflavoredBuildTarget(), target);
        }
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

  private BuildRuleType parseBuildRuleTypeFromRawRule(
      Cell cell,
      Map<String, Object> map) {
    String type = (String) Preconditions.checkNotNull(
        map.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return cell.getBuildRuleType(type);
  }

  /**
   * @param map the map of values that define the rule.
   * @return the build target defined by the rule.
   */
  private UnflavoredBuildTarget parseBuildTargetFromRawRule(
      Path cellRoot,
      Map<String, Object> map) {
    String basePath = (String) Preconditions.checkNotNull(map.get("buck.base_path"));
    String name = (String) Preconditions.checkNotNull(map.get("name"));
    return UnflavoredBuildTarget.builder(UnflavoredBuildTarget.BUILD_TARGET_PREFIX + basePath, name)
        .setCellPath(cellRoot)
        .build();
  }

  public void invalidateBasedOn(WatchEvent<?> event) throws InterruptedException {
    if (!WatchEvents.isPathChangeEvent(event)) {
      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      LOG.debug("Parser invalidating entire cache on overflow.");

      invalidateAllCaches();
      return;
    }

    Path path = (Path) event.context();

    for (Cell cell : knownCells) {
      try {
        if (isPathCreateOrDeleteEvent(event)) {
          BuildFileTree buildFiles = buildFileTrees.get(cell);

          if (path.endsWith(cell.getBuildFileName())) {
            // If a build file has been added or removed, reconstruct the build file tree.
            buildFileTrees.invalidate(cell);
          }

          // Added or removed files can affect globs, so invalidate the package build file
          // "containing" {@code path} unless its filename matches a temp file pattern.
          if (!isTempFile(cell, path)) {
            invalidateContainingBuildFile(cell, buildFiles, path);
          }

          LOG.verbose("Invalidating dependents for path %s, cache state %s", path, this);
        }
      } catch (ExecutionException | UncheckedExecutionException e) {
        try {
          throw propagate(e);
        } catch (BuildFileParseException bfpe) {
          LOG.warn("Unable to parse already parsed build file.", bfpe);
        }
      }
    }

    invalidatePath(path);
  }

  public void invalidatePath(Path path) throws InterruptedException {
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
    // Paths from Watchman are not absolute.
    path = cell.getFilesystem().resolve(path);

    // If the path is a build file for the cell, nuke the targets that it owns first. We don't need
    // to check whether or not the path ends in the build file name, since we know that these are
    // the only things that get added. Which makes for an easy life.
    List<Map<String, Object>> rawNodes = allRawNodes.getIfPresent(path);
    if (rawNodes != null) {
      // Invalidate the target nodes first
      for (Map<String, Object> rawNode : rawNodes) {
        UnflavoredBuildTarget target = parseBuildTargetFromRawRule(cell.getRoot(), rawNode);
        allTargetNodes.invalidateAll(targetsCornucopia.get(target));
        targetsCornucopia.removeAll(target);
      }

      // And then the raw node itself.
      allRawNodes.invalidate(path);
    }

    // We may have been given a file that other build files depend on. Iteratively remove those.
    Iterable<Path> dependents = buildFileDependents.get(path);
    for (Path dependent : dependents) {
      if (dependent.equals(path)) {
        continue;
      }
      invalidatePath(cell, dependent);
    }
    buildFileDependents.removeAll(path);
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

  private void invalidateIfProjectBuildFileParserStateChanged(Cell cell) {
    ImmutableMap<String, String> cellEnv = cell.getBuckConfig().getEnvironment();
    Iterable<String> defaultIncludes = new ParserConfig(cell.getBuckConfig()).getDefaultIncludes();

    boolean invalidateCaches = false;
    try (AutoCloseableLock updateLock = cachedStateLock.updateLock()) {
      Iterable<String> expected = cachedIncludes.get(cell.getRoot());

      if (!cellEnv.equals(cachedEnvironment)) {
        // Contents of System.getenv() have changed. Cowardly refuse to accept we'll parse
        // everything the same way.
        invalidateCaches = true;
      } else if (expected == null || !Iterables.elementsEqual(defaultIncludes, expected)) {
        // Someone's changed the default includes. That's almost definitely caused all our lovingly
        // cached data to be enormously wonky.
        invalidateCaches = true;
      }

      if (!invalidateCaches) {
        return;
      }

      try (AutoCloseableLock writeLock = cachedStateLock.writeLock()) {
        cachedEnvironment = cellEnv;
        cachedIncludes.put(cell.getRoot(), defaultIncludes);
      }
    }
    synchronized (this) {
      invalidateAllCaches();
      knownCells.add(cell);
    }
  }

  private synchronized void invalidateAllCaches() {
    LOG.debug("Invalidating all caches");
    allTargetNodes.invalidateAll();
    targetsCornucopia.clear();
    allRawNodes.invalidateAll();
    buildFileDependents.clear();
    knownCells.clear();
  }

  @Override
  public String toString() {
    return String.format(
        "memoized=%s known-cells=%s",
        allTargetNodes,
        knownCells);
  }
}
