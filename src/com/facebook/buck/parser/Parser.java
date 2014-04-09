/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.common.io.InputSupplier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery.  Primarily responsible for producing a
 * {@link DependencyGraph} based on a set of targets.  Also exposes some low-level facilities to
 * parse individual build files. Caches build rules to minimise the number of calls to python and
 * processes filesystem WatchEvents to invalidate the cache as files change. Expected to be used
 * from a single thread, so methods are not synchronized or thread safe.
 */
public class Parser {

  private final BuildTargetParser buildTargetParser;

  /**
   * The build files that have been parsed and whose build rules are in {@link #knownBuildTargets}.
   */
  private final ListMultimap<Path, Map<String, Object>> parsedBuildFiles;
  private final Map<BuildTarget, Path> targetsToFile;
  private final ImmutableSet<Pattern> tempFilePatterns;

  /**
   * True if all build files have been parsed and so all rules are in {@link #knownBuildTargets}.
   */
  private boolean allBuildFilesParsed;

  /**
   * Files included by build files. If the default includes are changed, then build files need to be
   * reevaluated with the new includes, so the includes used when populating the rule cache are
   * stored between requests to parse build files and the cache is invalidated and build files
   * reevaluated if the includes change.
   */
  @Nullable
  private List<String> cacheDefaultIncludes;

  /**
   * We parse a build file in search for one particular rule; however, we also keep track of the
   * other rules that were also parsed from it.
   */
  // TODO(user): Stop caching these in addition to parsedBuildFiles?
  private final Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets;

  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final ProjectBuildFileParserFactory buildFileParserFactory;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;
  private Console console;

  /**
   * Key of the meta-rule that lists the build files executed while reading rules.
   * The value is a list of strings with the root build file as the head and included
   * build files as the tail, for example: {"__includes":["/jimp/BUCK", "/jimp/buck_includes"]}
   */
  private static final String INCLUDES_META_RULE = "__includes";

  /**
   * A map from absolute included files ({@code /jimp/BUILD_DEFS}, for example) to the build files
   * that depend on them (typically {@code /jimp/BUCK} files).
   */
  private final ListMultimap<Path, Path> buildFileDependents;

  /**
   * A BuckEvent used to record the parse start time, which should include the WatchEvent
   * processing that occurs before the BuildTargets required to build a full ParseStart event are
   * known.
   */
  private Optional<BuckEvent> parseStartEvent = Optional.absent();

  /**
   * Parsers may be reused on different consoles, so need to allow the console to be set.
   * @param console The new console that the Parser should use.
   */
  public synchronized void setConsole(Console console) {
    this.console = console;
  }

  /**
   * A cached BuildFileTree which can be invalidated and lazily constructs new BuildFileTrees.
   * TODO(user): refactor this as a generic CachingSupplier<T> when it's needed elsewhere.
   */
  @VisibleForTesting
  static class BuildFileTreeCache implements InputSupplier<BuildFileTree> {
    private final InputSupplier<BuildFileTree> supplier;
    @Nullable private BuildFileTree buildFileTree;
    private BuildId currentBuildId = new BuildId();
    private BuildId buildTreeBuildId = new BuildId();

    /**
     * @param buildFileTreeSupplier each call to get() must reconstruct the tree from disk.
     */
    public BuildFileTreeCache(InputSupplier<BuildFileTree> buildFileTreeSupplier) {
      this.supplier = Preconditions.checkNotNull(buildFileTreeSupplier);
    }

    /**
     * Invalidate the current build file tree if it was not created during this build.
     * If the BuildFileTree was created during the current build it is still valid and
     * recreating it would generate an identical tree.
     */
    public void invalidateIfStale() {
      if (!currentBuildId.equals(buildTreeBuildId)) {
        buildFileTree = null;
      }
    }

    /**
     * @return the cached BuildFileTree, or a new lazily constructed BuildFileTree.
     */
    @Override
    public BuildFileTree getInput() throws IOException {
      if (buildFileTree == null) {
        buildTreeBuildId = currentBuildId;
        buildFileTree = supplier.getInput();
      }
      return buildFileTree;
    }

    /**
     * Stores the current build id, which is used to determine when the BuildFileTree is invalid.
     */
    public void onCommandStartedEvent(BuckEvent event) {
      // Ideally, the type of event would be CommandEvent.Started, but that would introduce
      // a dependency on com.facebook.buck.cli.
      Preconditions.checkArgument(event.getEventName().equals("CommandStarted"),
          "event should be of type CommandEvent.Started, but was: %s.",
          event);
      currentBuildId = event.getBuildId();
    }
  }
  private final BuildFileTreeCache buildFileTreeCache;

  public Parser(final ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      Console console,
      String pythonInterpreter,
      ImmutableSet<Pattern> tempFilePatterns,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this(projectFilesystem,
        buildRuleTypes,
        console,
        /* Calls to get() will reconstruct the build file tree by calling constructBuildFileTree. */
        new InputSupplier<BuildFileTree>() {
          @Override
          public BuildFileTree getInput() throws IOException {
            return BuildFileTree.constructBuildFileTree(projectFilesystem);
          }
        },
        new BuildTargetParser(projectFilesystem),
         /* knownBuildTargets */ Maps.<BuildTarget, BuildRuleBuilder<?>>newHashMap(),
        new DefaultProjectBuildFileParserFactory(
            projectFilesystem,
            pythonInterpreter,
            buildRuleTypes.getAllDescriptions()),
        tempFilePatterns,
        ruleKeyBuilderFactory);
  }

  /**
   * @param buildFileTreeSupplier each call to getInput() must reconstruct the build file tree from
   *     disk.
   */
  @VisibleForTesting
  Parser(ProjectFilesystem projectFilesystem,
         KnownBuildRuleTypes buildRuleTypes,
         Console console,
         InputSupplier<BuildFileTree> buildFileTreeSupplier,
         BuildTargetParser buildTargetParser,
         Map<BuildTarget, BuildRuleBuilder<?>> knownBuildTargets,
         ProjectBuildFileParserFactory buildFileParserFactory,
         ImmutableSet<Pattern> tempFilePatterns,
         RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.console = Preconditions.checkNotNull(console);
    this.buildFileTreeCache = new BuildFileTreeCache(
        Preconditions.checkNotNull(buildFileTreeSupplier));
    this.knownBuildTargets = Maps.newHashMap(Preconditions.checkNotNull(knownBuildTargets));
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
    this.buildFileParserFactory = Preconditions.checkNotNull(buildFileParserFactory);
    this.ruleKeyBuilderFactory = Preconditions.checkNotNull(ruleKeyBuilderFactory);
    this.parsedBuildFiles = ArrayListMultimap.create();
    this.targetsToFile = Maps.newHashMap();
    this.buildFileDependents = ArrayListMultimap.create();
    this.tempFilePatterns = tempFilePatterns;
  }

  public BuildTargetParser getBuildTargetParser() {
    return buildTargetParser;
  }

  public File getProjectRoot() {
    return projectFilesystem.getProjectRoot();
  }

  /**
   * The rules in a build file are cached if that specific build file was parsed or all build
   * files in the project were parsed and the includes haven't changed since the rules were
   * cached.
   *
   * @param buildFile the build file to look up in the {@link #parsedBuildFiles} cache.
   * @param includes the files to include before executing the build file.
   * @return true if the build file has already been parsed and its rules are cached.
   */
  private boolean isCached(File buildFile, Iterable<String> includes) {
    return !invalidateCacheOnIncludeChange(includes) && (allBuildFilesParsed ||
        parsedBuildFiles.containsKey(normalize(buildFile.toPath())));
  }

  /**
   * The cache is complete if all build files in the project were parsed and the includes haven't
   * changed since the rules were cached.
   *
   * @param includes the files to include before executing the build file.
   * @return true if all build files have already been parsed and their rules are cached.
   */
  private boolean isCacheComplete(Iterable<String> includes) {
    return !invalidateCacheOnIncludeChange(includes) && allBuildFilesParsed;
  }

  /**
   * Invalidates the cached build rules if {@code includes} have changed since the last call.
   * If the cache is invalidated the new {@code includes} used to build the new cache are stored.
   *
   * @param includes the files to include before executing the build file.
   * @return true if the cache was invalidated, false if the cache is still valid.
   */
  private synchronized boolean invalidateCacheOnIncludeChange(Iterable<String> includes) {
    List<String> includesList = Lists.newArrayList(includes);
    if (!includesList.equals(this.cacheDefaultIncludes)) {
      invalidateCache();
      this.cacheDefaultIncludes = includesList;
      return true;
    }
    return false;
  }

  private synchronized void invalidateCache() {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().println("Parser invalidating entire cache");
    }
    parsedBuildFiles.clear();
    knownBuildTargets.clear();
    allBuildFilesParsed = false;
  }

  /**
   * @param buildTargets the build targets to generate a dependency graph for.
   * @param defaultIncludes the files to include before executing build files.
   * @param eventBus used to log events while parsing.
   * @return the dependency graph containing the build targets and their related targets.
   */
  public DependencyGraph parseBuildFilesForTargets(
      Iterable<BuildTarget> buildTargets,
      Iterable<String> defaultIncludes,
      BuckEventBus eventBus)
      throws BuildFileParseException, BuildTargetException, IOException {
    // Make sure that knownBuildTargets is initially populated with the BuildRuleBuilders for the
    // seed BuildTargets for the traversal.
    postParseStartEvent(buildTargets, eventBus);
    DependencyGraph graph = null;
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createParser(
                 defaultIncludes,
                 EnumSet.of(ProjectBuildFileParser.Option.STRIP_NULL),
                 console)) {
      if (!isCacheComplete(defaultIncludes)) {
        Set<File> buildTargetFiles = Sets.newHashSet();
        for (BuildTarget buildTarget : buildTargets) {
          File buildFile = buildTarget.getBuildFile(projectFilesystem);
          boolean isNewElement = buildTargetFiles.add(buildFile);
          if (isNewElement) {
            parseBuildFile(buildFile, defaultIncludes, buildFileParser);
          }
        }
      }

      graph = findAllTransitiveDependencies(buildTargets, defaultIncludes, buildFileParser);
      return graph;
    } finally {
      eventBus.post(ParseEvent.finished(buildTargets, Optional.fromNullable(graph)));
    }
  }

  @VisibleForTesting
  DependencyGraph onlyUseThisWhenTestingToFindAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes) throws IOException {
    ProjectBuildFileParser parser = buildFileParserFactory.createParser(
        defaultIncludes,
        EnumSet.noneOf(ProjectBuildFileParser.Option.class),
        console);
    return findAllTransitiveDependencies(toExplore, defaultIncludes, parser);
  }


  /**
   * @param toExplore BuildTargets whose dependencies need to be explored.
   */
  @VisibleForTesting
  private DependencyGraph findAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes,
      final ProjectBuildFileParser buildFileParser) throws IOException {
    final BuildRuleResolver ruleResolver = new BuildRuleResolver();
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<>();

    AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {
          @Override
          protected Iterator<BuildTarget> findChildren(BuildTarget buildTarget) throws IOException {
            ParseContext parseContext = ParseContext.forBaseName(buildTarget.getBaseName());

            // Verify that the BuildTarget actually exists in the map of known BuildTargets
            // before trying to recurse through its children.
            if (!knownBuildTargets.containsKey(buildTarget)) {
              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(buildTarget, parseContext));
            }

            BuildRuleBuilder<?> buildRuleBuilder = knownBuildTargets.get(buildTarget);

            Set<BuildTarget> deps = Sets.newHashSet();
            for (BuildTarget buildTargetForDep : buildRuleBuilder.getDeps()) {
              try {
                if (!knownBuildTargets.containsKey(buildTargetForDep)) {
                  parseBuildFileContainingTarget(
                      buildTarget,
                      buildTargetForDep,
                      defaultIncludes,
                      buildFileParser);
                }
                deps.add(buildTargetForDep);
              } catch (BuildTargetException | BuildFileParseException e) {
                throw new HumanReadableException(e);
              }
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildTarget buildTarget) {
            BuildRuleBuilder<?> builderForTarget = knownBuildTargets.get(buildTarget);
            BuildRule buildRule = ruleResolver.buildAndAddToIndex(builderForTarget);

            // If the rule has any flavored deps, then add the appropriate edges to the graph.
            for (BuildRule dep : buildRule.getDeps()) {
              if (dep.getBuildTarget().isFlavored()) {
                addGraphEnhancedDeps(buildRule);
                break;
              }
            }

            // Update the graph.
            if (buildRule.getDeps().isEmpty()) {
              // If a build rule with no deps is specified as the build target to build, then make
              // sure it is in the graph.
              graph.addNode(buildRule);
            } else {
              for (BuildRule dep : buildRule.getDeps()) {
                graph.addEdge(buildRule, dep);
              }
            }
          }

          private void addGraphEnhancedDeps(BuildRule buildRule) {
            // If the builder for the build rule used graph enhancement to insert additional
            // dependencies, then new rules should have been added to the ruleResolver by the build
            // rule. Use a visitor to find such rules and make sure they are added to the
            // MutableDirectedGraph.
            new AbstractDependencyVisitor(buildRule) {

              @Override
              public ImmutableSet<BuildRule> visit(BuildRule rule) {
                // Most of the time, this will not be used in the call to visit(), so set it to
                // null by default.
                ImmutableSet.Builder<BuildRule> depsToVisit = null;
                boolean isRuleFlavored = rule.getBuildTarget().isFlavored();

                for (BuildRule dep : rule.getDeps()) {
                  // If either the rule or the dep is flavored, then add an edge for it.
                  boolean isDepFlavored = dep.getBuildTarget().isFlavored();
                  if (isRuleFlavored || isDepFlavored) {
                    graph.addEdge(rule, dep);
                  }

                  // If the dep is flavored, then visit it.
                  if (isDepFlavored) {
                    if (depsToVisit == null) {
                      depsToVisit = ImmutableSet.builder();
                    }
                    depsToVisit.add(dep);
                  }
                }

                return depsToVisit == null ? ImmutableSet.<BuildRule>of() : depsToVisit.build();
              }

            }.start();
          }

          @Override
          protected void onTraversalComplete(
              Iterable<BuildTarget> nodesInExplorationOrder) {
          }
    };

    try {
      traversal.traverse(toExplore);
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    }

    return new DependencyGraph(graph);
  }

  /**
   * Note that if this Parser is populated via
   * {@link #filterAllTargetsInProject}, then this method should not be called.
   */
  private void parseBuildFileContainingTarget(
      BuildTarget sourceTarget,
      BuildTarget buildTarget,
      Iterable<String> defaultIncludes,
      ProjectBuildFileParser buildFileParser)
      throws BuildFileParseException, BuildTargetException, IOException {
    if (isCacheComplete(defaultIncludes)) {
      // In this case, all of the build rules should have been loaded into the knownBuildTargets
      // Map before this method was invoked. Therefore, there should not be any more build files to
      // parse. This must be the result of traversing a non-existent dep in a build rule, so an
      // error is reported to the user. Unfortunately, the source of the build file where the
      // non-existent rule was declared is not known at this point, which is why it is not included
      // in the error message. The best we can do is tell the user the target that included target
      // as a dep.
      throw new HumanReadableException(
          "Unable to locate dependency \"%s\" for target \"%s\"", buildTarget, sourceTarget);
    }

    File buildFile = buildTarget.getBuildFile(projectFilesystem);
    if (isCached(buildFile, defaultIncludes)) {
      throw new HumanReadableException(
          "The build file that should contain %s has already been parsed (%s), " +
              "but %s was not found. Please make sure that %s is defined in %s.",
          buildTarget,
          buildFile,
          buildTarget,
          buildTarget,
          buildFile);
    }

    parseBuildFile(buildFile, defaultIncludes, buildFileParser);
  }

  public List<Map<String, Object>> parseBuildFile(
      File buildFile,
      Iterable<String> defaultIncludes,
      EnumSet<ProjectBuildFileParser.Option> parseOptions)
      throws BuildFileParseException, BuildTargetException, IOException {
    try (ProjectBuildFileParser projectBuildFileParser =
        buildFileParserFactory.createParser(
            defaultIncludes,
            parseOptions,
            console)) {
      return parseBuildFile(buildFile, defaultIncludes, projectBuildFileParser);
    }
  }

  /**
   * @param buildFile the build file to execute to generate build rules if they are not cached.
   * @param defaultIncludes the files to include before executing the build file.
   * @return a list of raw build rules generated by executing the build file.
   */
  public List<Map<String, Object>> parseBuildFile(
      File buildFile,
      Iterable<String> defaultIncludes,
      ProjectBuildFileParser buildFileParser)
      throws BuildFileParseException, BuildTargetException, IOException {
    Preconditions.checkNotNull(buildFile);
    Preconditions.checkNotNull(defaultIncludes);
    Preconditions.checkNotNull(buildFileParser);

    if (!isCached(buildFile, defaultIncludes)) {
      if (console.getVerbosity().shouldPrintCommand()) {
        console.getStdErr().printf("Parsing %s file: %s\n",
            BuckConstant.BUILD_RULES_FILE_NAME,
            buildFile);
      }

      parseRawRulesInternal(buildFileParser.getAllRulesAndMetaRules(buildFile.toPath()));
    }
    return parsedBuildFiles.get(normalize(buildFile.toPath()));
  }

  /**
   * @param rules the raw rule objects to parse.
   */
  @VisibleForTesting
  synchronized void parseRawRulesInternal(Iterable<Map<String, Object>> rules)
      throws BuildTargetException, IOException {
    for (Map<String, Object> map : rules) {

      if (isMetaRule(map)) {
        parseMetaRule(map);
        continue;
      }

      BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
      BuildTarget target = parseBuildTargetFromRawRule(map);
      targetsToFile.put(
          target,
          normalize(Paths.get((String) map.get("buck.base_path")))
              .resolve("BUCK").toAbsolutePath());

      BuildRuleFactory<?> factory = buildRuleTypes.getFactory(buildRuleType);
      if (factory == null) {
        throw new HumanReadableException("Unrecognized rule %s while parsing %s.",
            buildRuleType,
            target.getBuildFile(projectFilesystem));
      }

      BuildFileTree buildFileTree;
      buildFileTree = buildFileTreeCache.getInput();

      BuildRuleBuilder<?> buildRuleBuilder;
      BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
          map,
          projectFilesystem,
          buildFileTree,
          buildTargetParser,
          target,
          ruleKeyBuilderFactory);
      buildRuleBuilder = factory.newInstance(factoryParams);
      Object existingRule = knownBuildTargets.put(target, buildRuleBuilder);
      if (existingRule != null) {
        throw new RuntimeException("Duplicate definition for " + target.getFullyQualifiedName());
      }
      parsedBuildFiles.put(normalize(target.getBuildFile(projectFilesystem).toPath()), map);
    }
  }

  /**
   * @param map a build rule read from a build file.
   * @return true if map represents a meta rule.
   */
  private boolean isMetaRule(Map<String, Object> map) {
    return map.containsKey(INCLUDES_META_RULE);
  }

  /**
   * Processes build file meta rules and returns true if map represents a meta rule.
   * @param map a meta rule read from a build file.
   */
  @SuppressWarnings("unchecked") // Needed for downcast from Object to List<String>.
  private boolean parseMetaRule(Map<String, Object> map) {
    Preconditions.checkState(isMetaRule(map));

    // INCLUDES_META_RULE maps to a list of file paths: the head is a
    // dependent build file and the tail is a list of the files it includes.
    List<String> fileNames = ((List<String>) map.get(INCLUDES_META_RULE));
    Path dependent = normalize(new File(fileNames.get(0)).toPath());
    for (String fileName : fileNames) {
      buildFileDependents.put(normalize(new File(fileName).toPath()), dependent);
    }
    return true;
  }

  /**
   * This method has been deprecated because it is not idempotent and returns different results
   * based upon what has been already parsed.
   * Prefer {@link #filterGraphTargets(RawRulePredicate, DependencyGraph)}
   *
   * @param filter the test to apply to all targets that have been read from build files, or null.
   * @return the build targets that pass the test, or null if the filter was null.
   */
  @VisibleForTesting
  @Nullable
  List<BuildTarget> filterTargets(@Nullable RawRulePredicate filter)
      throws NoSuchBuildTargetException {
    if (filter == null) {
      return null;
    }

    List<BuildTarget> matchingTargets = Lists.newArrayList();
    for (Map<String, Object> map : parsedBuildFiles.values()) {
      BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
      BuildTarget target = parseBuildTargetFromRawRule(map);
      if (filter.isMatch(map, buildRuleType, target)) {
        matchingTargets.add(target);
      }
    }

    return matchingTargets;
  }

  /**
   * @param filter the test to apply to all targets that have been read from build files, or null.
   * @return the build targets that pass the test, or null if the filter was null.
   */
  @VisibleForTesting
  @Nullable
  Iterable<BuildTarget> filterGraphTargets(
      @Nullable RawRulePredicate filter,
      DependencyGraph dependencyGraph) throws NoSuchBuildTargetException {
    if (filter == null) {
      return null;
    }

    ImmutableSet.Builder<BuildTarget> matchingTargets = ImmutableSet.builder();
    for (BuildRule buildRule : dependencyGraph.getNodes()) {
      for (Map<String, Object> map :
           parsedBuildFiles.get(targetsToFile.get(buildRule.getBuildTarget()))) {
        BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
        BuildTarget target = parseBuildTargetFromRawRule(map);
        if (filter.isMatch(map, buildRuleType, target)) {
          matchingTargets.add(target);
        }
      }
    }

    return matchingTargets.build();
  }

  /**
   * @param map the map of values that define the rule.
   * @return the type of rule defined by the map.
   */
  private BuildRuleType parseBuildRuleTypeFromRawRule(Map<String, Object> map) {
    String type = (String) map.get("type");
    return buildRuleTypes.getBuildRuleType(type);
  }

  /**
   * @param map the map of values that define the rule.
   * @return the build target defined by the rule.
   */
  private BuildTarget parseBuildTargetFromRawRule(Map<String, Object> map) {
    String basePath = (String) map.get("buck.base_path");
    String name = (String) map.get("name");
    return new BuildTarget("//" + basePath, name);
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct a
   * dependency graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @param filesystem The project filesystem.
   * @param includes A list of files that should be included by each build file.
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   * @return The build targets in the project filtered by the given filter.
   */
  public synchronized List<BuildTarget> filterAllTargetsInProject(ProjectFilesystem filesystem,
                                                     Iterable<String> includes,
                                                     @Nullable RawRulePredicate filter)
      throws BuildFileParseException, BuildTargetException, IOException {
    Preconditions.checkNotNull(filesystem);
    Preconditions.checkNotNull(includes);
    if (!projectFilesystem.getProjectRoot().equals(filesystem.getProjectRoot())) {
      throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
          projectFilesystem.getProjectRoot(), filesystem.getProjectRoot()));
    }
    if (!isCacheComplete(includes)) {
      knownBuildTargets.clear();
      parsedBuildFiles.clear();
      parseRawRulesInternal(
          ProjectBuildFileParser.getAllRulesInProject(
              buildFileParserFactory,
              includes,
              console));
      allBuildFilesParsed = true;
    }
    return filterTargets(filter);
  }


  /**
   * Takes a sequence of build targets and parses all of the build files that contain them and their
   * transitive deps, producing a collection of "raw rules" that have been produced from the build
   * files. The specified {@link RawRulePredicate} is applied to this collection. This method
   * returns the collection of {@link BuildTarget}s that correspond to the raw rules that were
   * matched by the predicate.
   * <p>
   * Because {@code project_config} rules are not transitive dependencies of rules such as
   * {@code android_binary}, but are defined in the same build files as the transitive
   * dependencies of an {@code android_binary}, this method is helpful in finding all of the
   * {@code project_config} rules needed to produce an IDE configuration to build said
   * {@code android_binary}. See {@link RawRulePredicates#matchBuildRuleType(BuildRuleType)} for an
   * example of such a {@link RawRulePredicate}.
   */
  public synchronized Iterable<BuildTarget> filterTargetsInProjectFromRoots(
      Iterable<BuildTarget> roots,
      Iterable<String> defaultIncludes,
      BuckEventBus eventBus,
      RawRulePredicate filter)
      throws BuildFileParseException, BuildTargetException, IOException {
    DependencyGraph dependencyGraph = parseBuildFilesForTargets(roots, defaultIncludes, eventBus);

    return filterGraphTargets(filter, dependencyGraph);
  }

  /**
   * Called when a new command is executed and used to signal to the BuildFileTreeCache
   * that reconstructing the build file tree may result in a different BuildFileTree.
   */
  @Subscribe
  public synchronized void onCommandStartedEvent(BuckEvent event) {
    // Ideally, the type of event would be CommandEvent.Started, but that would introduce
    // a dependency on com.facebook.buck.cli.
    Preconditions.checkArgument(
        event.getEventName().equals("CommandStarted"),
        "event should be of type CommandEvent.Started, but was: %s.",
        event);
    buildFileTreeCache.onCommandStartedEvent(event);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required. {@link Path}s contained within events must all be relative to the
   * {@link ProjectFilesystem} root.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchEvent<?> event) throws IOException {
    if (console.getVerbosity() == Verbosity.ALL) {
      console.getStdErr().printf("Parser watched event %s %s\n", event.kind(),
          projectFilesystem.createContextString(event));
    }

    if (projectFilesystem.isPathChangeEvent(event)) {
      Path path = (Path) event.context();

      if (isPathCreateOrDeleteEvent(event)) {

        if (path.endsWith(BuckConstant.BUILD_RULES_FILE_NAME)) {

          // If a build file has been added or removed, reconstruct the build file tree.
          buildFileTreeCache.invalidateIfStale();
        }

        // Added or removed files can affect globs, so invalidate the package build file
        // "containing" {@code path} unless its filename matches a temp file pattern.
        if (!isTempFile(path)) {
          invalidateContainingBuildFile(path);
        }
      }

      // Invalidate the raw rules and targets dependent on this file.
      invalidateDependents(path);

    } else {

      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      buildFileTreeCache.invalidateIfStale();
      invalidateCache();
    }
  }

  /**
   * @param path The {@link Path} to test.
   * @return true if {@code path} is a temporary or backup file.
   */
  private boolean isTempFile(Path path) {
    final String fileName = path.getFileName().toString();
    Predicate<Pattern> patternMatches = new Predicate<Pattern>() {
      @Override
      public boolean apply(Pattern pattern) {
        return pattern.matcher(fileName).matches();
      }
    };
    return Iterators.any(tempFilePatterns.iterator(), patternMatches);
  }

  /**
   * Finds the build file responsible for the given {@link Path} and invalidates
   * all of the cached rules dependent on it.
   * @param path A {@link Path}, relative to the project root and "contained"
   *             within the build file to find and invalidate.
   */
  private void invalidateContainingBuildFile(Path path) throws IOException {
    String packageBuildFilePath =
        buildFileTreeCache.getInput().getBasePathOfAncestorTarget(path.toString());
    invalidateDependents(
        projectFilesystem.getFileForRelativePath(
            packageBuildFilePath + '/' + BuckConstant.BUILD_RULES_FILE_NAME).toPath());
  }

  private boolean isPathCreateOrDeleteEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  /**
   * Remove the targets and rules defined by {@code path} from the cache and recursively remove the
   * targets and rules defined by files that transitively include {@code path} from the cache.
   * @param path The File that has changed.
   */
  private synchronized void invalidateDependents(Path path) {
    // Normalize path to ensure it hashes equally with map keys.
    path = normalize(path);

    if (parsedBuildFiles.containsKey(path)) {
      if (console.getVerbosity() == Verbosity.ALL) {
        console.getStdErr().printf("Parser invalidating %s cache\n",
            path.toAbsolutePath());
      }

      // Remove all targets defined by path from cache.
      for (Map<String, Object> rawRule : parsedBuildFiles.get(path)) {
        BuildTarget target = parseBuildTargetFromRawRule(rawRule);
        knownBuildTargets.remove(target);
      }

      // Remove all rules defined in path from cache.
      parsedBuildFiles.removeAll(path);

      // All targets have no longer been parsed and cached.
      allBuildFilesParsed = false;
    }

    // Recursively invalidate dependents.
    for (Path dependent : buildFileDependents.get(path)) {

      if (!dependent.equals(path)) {
        invalidateDependents(dependent);
      }
    }

    // Dependencies will be repopulated when files are re-parsed.
    buildFileDependents.removeAll(path);
  }

  /**
   * Always use Files created from absolute paths as they are returned from buck.py and must be
   * created from consistent paths to be looked up correctly in maps.
   * @param path A File to normalize.
   * @return An equivalent file constructed from a normalized, absolute path to the given File.
   */
  private Path normalize(Path path) {
    return projectFilesystem.resolve(path);
  }

  /**
   * Record the parse start time, which should include the WatchEvent processing that occurs
   * before the BuildTargets required to build a full ParseStart event are known.
   */
  public void recordParseStartTime(BuckEventBus eventBus) {
    class ParseStartTime extends AbstractBuckEvent {

      @Override
      protected String getValueString() {
        return "Timestamp.";
      }

      @Override
      public boolean eventsArePair(BuckEvent event) {
        return false;
      }

      @Override
      public String getEventName() {
        return "ParseStartTime";
      }
    }
    parseStartEvent = Optional.<BuckEvent>of(new ParseStartTime());
    eventBus.timestamp(parseStartEvent.get());
  }

  /**
   * @return an Optional BuckEvent timestamped with the parse start time.
   */
  public Optional<BuckEvent> getParseStartTime() {
    return parseStartEvent;
  }

  /**
   * Post a ParseStart event to eventBus, using the start of WatchEvent processing as the start
   * time if applicable.
   */
  private void postParseStartEvent(Iterable<BuildTarget> buildTargets, BuckEventBus eventBus) {
    if (parseStartEvent.isPresent()) {
      eventBus.post(ParseEvent.started(buildTargets), parseStartEvent.get());
    } else {
      eventBus.post(ParseEvent.started(buildTargets));
    }
  }
}
