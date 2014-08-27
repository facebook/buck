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
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery.  Primarily responsible for producing a
 * {@link ActionGraph} based on a set of targets.  Also exposes some low-level facilities to
 * parse individual build files. Caches build rules to minimise the number of calls to python and
 * processes filesystem WatchEvents to invalidate the cache as files change.
 */
public class Parser {

  private final BuildTargetParser buildTargetParser;

  private final CachedState state;

  private final ImmutableSet<Pattern> tempFilePatterns;

  /**
   * True if all build files have been parsed and so all rules are in the {@link CachedState}.
   */
  private boolean allBuildFilesParsed;

  private final Repository repository;
  private final ProjectBuildFileParserFactory buildFileParserFactory;
  private final RuleKeyBuilderFactory ruleKeyBuilderFactory;

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

  private static final Logger LOG = Logger.get(Parser.class);

  /**
   * A cached BuildFileTree which can be invalidated and lazily constructs new BuildFileTrees.
   * TODO(user): refactor this as a generic CachingSupplier<T> when it's needed elsewhere.
   */
  @VisibleForTesting
  static class BuildFileTreeCache implements Supplier<BuildFileTree> {
    private final Supplier<BuildFileTree> supplier;
    @Nullable private BuildFileTree buildFileTree;
    private BuildId currentBuildId = new BuildId();
    private BuildId buildTreeBuildId = new BuildId();

    /**
     * @param buildFileTreeSupplier each call to get() must reconstruct the tree from disk.
     */
    public BuildFileTreeCache(Supplier<BuildFileTree> buildFileTreeSupplier) {
      this.supplier = Preconditions.checkNotNull(buildFileTreeSupplier);
    }

    /**
     * Invalidate the current build file tree if it was not created during this build.
     * If the BuildFileTree was created during the current build it is still valid and
     * recreating it would generate an identical tree.
     */
    public synchronized void invalidateIfStale() {
      if (!currentBuildId.equals(buildTreeBuildId)) {
        buildFileTree = null;
      }
    }

    /**
     * @return the cached BuildFileTree, or a new lazily constructed BuildFileTree.
     */
    @Override
    public synchronized BuildFileTree get() {
      if (buildFileTree == null) {
        buildTreeBuildId = currentBuildId;
        buildFileTree = supplier.get();
      }
      return buildFileTree;
    }

    /**
     * Stores the current build id, which is used to determine when the BuildFileTree is invalid.
     */
    public synchronized void onCommandStartedEvent(BuckEvent event) {
      // Ideally, the type of event would be CommandEvent.Started, but that would introduce
      // a dependency on com.facebook.buck.cli.
      Preconditions.checkArgument(event.getEventName().equals("CommandStarted"),
          "event should be of type CommandEvent.Started, but was: %s.",
          event);
      currentBuildId = event.getBuildId();
    }
  }
  private final BuildFileTreeCache buildFileTreeCache;

  public Parser(
      final Repository repository,
      String pythonInterpreter,
      ImmutableSet<Pattern> tempFilePatterns,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this(repository,
        /* Calls to get() will reconstruct the build file tree by calling constructBuildFileTree. */
        // TODO(simons): Consider momoizing the suppler.
        new Supplier<BuildFileTree>() {
          @Override
          public BuildFileTree get() {
            return new FilesystemBackedBuildFileTree(repository.getFilesystem());
          }
        },
        // TODO(jacko): Get rid of this global BuildTargetParser completely.
        repository.getBuildTargetParser(),
        new DefaultProjectBuildFileParserFactory(
            repository.getFilesystem(),
            pythonInterpreter,
            repository.getAllDescriptions()),
        tempFilePatterns,
        ruleKeyBuilderFactory);
  }

  /**
   * @param buildFileTreeSupplier each call to getInput() must reconstruct the build file tree from
   */
  @VisibleForTesting
  Parser(
      Repository repository,
      Supplier<BuildFileTree> buildFileTreeSupplier,
      BuildTargetParser buildTargetParser,
      ProjectBuildFileParserFactory buildFileParserFactory,
      ImmutableSet<Pattern> tempFilePatterns,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    this.repository = Preconditions.checkNotNull(repository);
    this.buildFileTreeCache = new BuildFileTreeCache(
        Preconditions.checkNotNull(buildFileTreeSupplier));
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
    this.buildFileParserFactory = Preconditions.checkNotNull(buildFileParserFactory);
    this.ruleKeyBuilderFactory = Preconditions.checkNotNull(ruleKeyBuilderFactory);
    this.buildFileDependents = ArrayListMultimap.create();
    this.tempFilePatterns = tempFilePatterns;
    this.state = new CachedState();
  }

  public BuildTargetParser getBuildTargetParser() {
    return buildTargetParser;
  }

  public Path getProjectRoot() {
    return repository.getFilesystem().getRootPath();
  }

  /**
   * The rules in a build file are cached if that specific build file was parsed or all build
   * files in the project were parsed and the includes and environment haven't changed since the
   * rules were cached.
   *
   * @param buildFile the build file to look up in the {@link CachedState}.
   * @param includes the files to include before executing the build file.
   * @param env the environment to execute the build file in.
   * @return true if the build file has already been parsed and its rules are cached.
   */
  private synchronized boolean isCached(
      Path buildFile,
      Iterable<String> includes,
      ImmutableMap<String, String> env) {
    boolean includesChanged = state.invalidateCacheOnIncludeChange(includes);
    boolean environmentChanged = state.invalidateCacheOnEnvironmentChange(env);
    boolean fileParsed = state.isParsed(buildFile);
    return !includesChanged && !environmentChanged && (allBuildFilesParsed || fileParsed);
  }

  /**
   * The cache is complete if all build files in the project were parsed and the includes and
   * environment haven't changed since the rules were cached.
   *
   * @param includes the files to include before executing the build file.
   * @param env the environment to execute the build file in.
   * @return true if all build files have already been parsed and their rules are cached.
   */
  private synchronized boolean isCacheComplete(
      Iterable<String> includes,
      ImmutableMap<String, String> env) {
    boolean includesChanged = state.invalidateCacheOnIncludeChange(includes);
    boolean environmentChanged = state.invalidateCacheOnEnvironmentChange(env);
    return !includesChanged && !environmentChanged && allBuildFilesParsed;
  }

  private synchronized void invalidateCache() {
    state.invalidateAll();
    allBuildFilesParsed = false;
  }

  /**
   * @param buildTargets the build targets to generate an action graph for.
   * @param defaultIncludes the files to include before executing build files.
   * @param eventBus used to log events while parsing.
   * @return the action graph containing the build targets and their related targets.
   */
  public synchronized ActionGraph parseBuildFilesForTargets(
      Iterable<BuildTarget> buildTargets,
      Iterable<String> defaultIncludes,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    // Make sure that knownBuildTargets is initially populated with the BuildRuleBuilders for the
    // seed BuildTargets for the traversal.
    postParseStartEvent(buildTargets, eventBus);
    ActionGraph graph = null;
    // TODO(jacko): Instantiating one ProjectBuildFileParser here isn't enough. We a collection of
    //              repo-specific parsers.
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createParser(
                 defaultIncludes,
                 console,
                 environment)) {
      if (!isCacheComplete(defaultIncludes, environment)) {
        Set<Path> buildTargetFiles = Sets.newHashSet();
        for (BuildTarget buildTarget : buildTargets) {
          Path buildFile = repository.getAbsolutePathToBuildFile(buildTarget);
          boolean isNewElement = buildTargetFiles.add(buildFile);
          if (isNewElement) {
            parseBuildFile(buildFile, defaultIncludes, buildFileParser, environment);
          }
        }
      }

      graph = findAllTransitiveDependencies(
          buildTargets,
          defaultIncludes,
          buildFileParser,
          environment);
      return graph;
    } finally {
      eventBus.post(ParseEvent.finished(buildTargets, Optional.fromNullable(graph)));
    }
  }

  @VisibleForTesting
  synchronized ActionGraph onlyUseThisWhenTestingToFindAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      Iterable<String> defaultIncludes,
      Console console,
      ImmutableMap<String, String> environment)
      throws IOException, BuildFileParseException, InterruptedException {
    try (ProjectBuildFileParser parser = buildFileParserFactory.createParser(
        defaultIncludes,
        console,
        environment)) {
      return findAllTransitiveDependencies(toExplore, defaultIncludes, parser, environment);
    }
  }

  /**
   * @param toExplore BuildTargets whose dependencies need to be explored.
   */
  @VisibleForTesting
  private ActionGraph findAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes,
      final ProjectBuildFileParser buildFileParser,
      final ImmutableMap<String, String> environment) throws IOException {

    final TargetGraph graph = buildTargetGraph(
        toExplore,
        defaultIncludes,
        buildFileParser,
        environment);

    return buildActionGraphFromTargetGraph(graph);
  }

  @Nullable
  public synchronized TargetNode<?> getTargetNode(BuildTarget buildTarget) {
    return state.get(buildTarget);
  }

  /**
   * Build a {@link TargetGraph} from the {@code toExplore} targets. Note that this graph isn't
   * pruned in any way and needs to be transformed into an {@link ActionGraph} before being useful
   * in a build. The TargetGraph is useful for commands such as
   * {@link com.facebook.buck.cli.AuditOwnerCommand} which only need to understand the relationship
   * between modules.
   * <p>
   * Note that this method does not cache results. Call either this or
   * {@link #buildActionGraphFromTargetGraph(TargetGraph)} in a single
   * {@link com.facebook.buck.rules.BuildEngine} execution.
   *
   * @param toExplore the {@link BuildTarget}s that {@link TargetGraph} is calculated for.
   * @param defaultIncludes the files to include before executing build files.
   * @param buildFileParser the parser for build files.
   * @return a {@link TargetGraph} containing all the nodes from {@code toExplore}.
   */
  public synchronized TargetGraph buildTargetGraph(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes,
      final ProjectBuildFileParser buildFileParser,
      final ImmutableMap<String, String> environment) throws IOException {
    final MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();

    AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {
          @Override
          protected Iterator<BuildTarget> findChildren(BuildTarget buildTarget) throws IOException {
            ParseContext parseContext = ParseContext.forBaseName(buildTarget.getBaseName());

            // Verify that the BuildTarget actually exists in the map of known BuildTargets
            // before trying to recurse through its children.
            TargetNode<?> targetNode = getTargetNode(buildTarget);
            if (targetNode == null) {
              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(buildTarget, parseContext));
            }

            Set<BuildTarget> deps = Sets.newHashSet();
            for (BuildTarget buildTargetForDep : targetNode.getDeps()) {
              try {
                TargetNode<?> depTargetNode = getTargetNode(buildTargetForDep);
                if (depTargetNode == null) {
                  parseBuildFileContainingTarget(
                      buildTarget,
                      buildTargetForDep,
                      defaultIncludes,
                      buildFileParser,
                      environment);
                  depTargetNode = getTargetNode(buildTargetForDep);
                  if (depTargetNode == null) {
                    throw new HumanReadableException(
                        NoSuchBuildTargetException.createForMissingBuildRule(
                            buildTargetForDep,
                            ParseContext.forBaseName(buildTargetForDep.getBaseName())));
                  }
                }
                depTargetNode.checkVisibility(buildTarget);
                deps.add(buildTargetForDep);
              } catch (BuildTargetException | BuildFileParseException e) {
                throw new HumanReadableException(e);
              }
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildTarget buildTarget) {
            TargetNode<?> targetNode = getTargetNode(buildTarget);
            Preconditions.checkNotNull(targetNode, "No target node found for %s", buildTarget);
            graph.addNode(targetNode);
            for (BuildTarget target : targetNode.getDeps()) {
              graph.addEdge(targetNode, getTargetNode(target));
            }
          }

          @Override
          protected void onTraversalComplete(Iterable<BuildTarget> nodesInExplorationOrder) {
          }
        };

    try {
      traversal.traverse(toExplore);
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    }

    return new TargetGraph(graph);
  }

  private synchronized ActionGraph buildActionGraphFromTargetGraph(final TargetGraph graph) {
    final BuildRuleResolver ruleResolver = new BuildRuleResolver();
    final MutableDirectedGraph<BuildRule> actionGraph = new MutableDirectedGraph<>();

    AbstractBottomUpTraversal<TargetNode<?>, ActionGraph> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?>, ActionGraph>(graph) {

          @Override
          public void visit(TargetNode<?> node) {
            TargetNodeToBuildRuleTransformer<?> transformer =
                new TargetNodeToBuildRuleTransformer<>(node);
            BuildRule rule;
            try {
              rule = transformer.transform(ruleResolver);
            } catch (NoSuchBuildTargetException e) {
              throw new HumanReadableException(e);
            }
            ruleResolver.addToIndex(rule.getBuildTarget(), rule);
            actionGraph.addNode(rule);

            for (BuildRule buildRule : rule.getDeps()) {
              if (buildRule.getBuildTarget().isFlavored()) {
                addGraphEnhancedDeps(rule);
              }
            }

            for (BuildRule dep : rule.getDeps()) {
              actionGraph.addEdge(rule, dep);
            }

          }

          @Override
          public ActionGraph getResult() {
            return new ActionGraph(actionGraph);
          }

          private void addGraphEnhancedDeps(BuildRule rule) {
            new AbstractDependencyVisitor(rule) {
              @Override
              public ImmutableSet<BuildRule> visit(BuildRule rule) {
                ImmutableSet.Builder<BuildRule> depsToVisit = null;
                boolean isRuleFlavored = rule.getBuildTarget().isFlavored();

                for (BuildRule dep : rule.getDeps()) {
                  boolean isDepFlavored = dep.getBuildTarget().isFlavored();
                  if (isRuleFlavored || isDepFlavored) {
                    actionGraph.addEdge(rule, dep);
                  }

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
        };

    bottomUpTraversal.traverse();
    return bottomUpTraversal.getResult();
  }

  /**
   * Note that if this Parser is populated via
   * {@link #filterAllTargetsInProject}, then this method should not be called.
   */
  private synchronized void parseBuildFileContainingTarget(
      BuildTarget sourceTarget,
      BuildTarget buildTarget,
      Iterable<String> defaultIncludes,
      ProjectBuildFileParser buildFileParser,
      ImmutableMap<String, String> environment)
      throws BuildFileParseException, BuildTargetException, IOException {
    if (isCacheComplete(defaultIncludes, environment)) {
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

    Path buildFile = repository.getAbsolutePathToBuildFile(buildTarget);
    if (isCached(buildFile, defaultIncludes, environment)) {
      throw new HumanReadableException(
          "The build file that should contain %s has already been parsed (%s), " +
              "but %s was not found. Please make sure that %s is defined in %s.",
          buildTarget,
          buildFile,
          buildTarget,
          buildTarget,
          buildFile);
    }

    parseBuildFile(buildFile, defaultIncludes, buildFileParser, environment);
  }

  public synchronized List<Map<String, Object>> parseBuildFile(
      Path buildFile,
      Iterable<String> defaultIncludes,
      ImmutableMap<String, String> environment,
      Console console)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    try (ProjectBuildFileParser projectBuildFileParser =
        buildFileParserFactory.createParser(
            defaultIncludes,
            console,
            environment)) {
      return parseBuildFile(buildFile, defaultIncludes, projectBuildFileParser, environment);
    }
  }

  /**
   * @param buildFile the build file to execute to generate build rules if they are not cached.
   * @param defaultIncludes the files to include before executing the build file.
   * @param environment the environment to execute the build file in.
   * @return a list of raw build rules generated by executing the build file.
   */
  public synchronized List<Map<String, Object>> parseBuildFile(
      Path buildFile,
      Iterable<String> defaultIncludes,
      ProjectBuildFileParser buildFileParser,
      ImmutableMap<String, String> environment)
      throws BuildFileParseException, BuildTargetException, IOException {
    Preconditions.checkNotNull(buildFile);
    Preconditions.checkNotNull(defaultIncludes);
    Preconditions.checkNotNull(buildFileParser);

    if (!isCached(buildFile, defaultIncludes, environment)) {
      LOG.debug("Parsing %s file: %s", BuckConstant.BUILD_RULES_FILE_NAME, buildFile);
      parseRawRulesInternal(buildFileParser.getAllRulesAndMetaRules(buildFile));
    } else {
      LOG.debug("Not parsing %s file (already in cache)", BuckConstant.BUILD_RULES_FILE_NAME);
    }
    return state.getRawRules(buildFile);
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

      BuildTarget target = parseBuildTargetFromRawRule(map);
      BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
      Description<?> description = repository.getDescription(buildRuleType);
      if (description == null) {
        throw new HumanReadableException("Unrecognized rule %s while parsing %s.",
            buildRuleType,
            repository.getAbsolutePathToBuildFile(target));
      }

      state.put(target, map);
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
  private synchronized boolean parseMetaRule(Map<String, Object> map) {
    Preconditions.checkState(isMetaRule(map));

    // INCLUDES_META_RULE maps to a list of file paths: the head is a
    // dependent build file and the tail is a list of the files it includes.
    List<String> fileNames = ((List<String>) map.get(INCLUDES_META_RULE));
    Path dependent = normalize(Paths.get(fileNames.get(0)));
    for (String fileName : fileNames) {
      buildFileDependents.put(normalize(Paths.get(fileName)), dependent);
    }
    return true;
  }

  /**
   * @param filter the test to apply to all targets that have been read from build files, or null.
   * @return the build targets that pass the test, or null if the filter was null.
   */
  @VisibleForTesting
  ImmutableSet<BuildTarget> filterTargets(RuleJsonPredicate filter)
      throws NoSuchBuildTargetException {

    return state.filterTargets(filter);
  }

  /**
   * @param map the map of values that define the rule.
   * @return the type of rule defined by the map.
   */
  private BuildRuleType parseBuildRuleTypeFromRawRule(Map<String, Object> map) {
    String type = (String) map.get("type");
    return repository.getBuildRuleType(type);
  }

  /**
   * @param map the map of values that define the rule.
   * @return the build target defined by the rule.
   */
  private BuildTarget parseBuildTargetFromRawRule(Map<String, Object> map) {
    String basePath = (String) map.get("buck.base_path");
    String name = (String) map.get("name");
    return BuildTarget.builder(BuildTarget.BUILD_TARGET_PREFIX + basePath, name).build();
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct an
   * action graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   * @param filesystem The project filesystem.
   * @param includes A list of files that should be included by each build file.
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   * @return The build targets in the project filtered by the given filter.
   */
  public synchronized ImmutableSet<BuildTarget> filterAllTargetsInProject(
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      RuleJsonPredicate filter,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    Preconditions.checkNotNull(filesystem);
    Preconditions.checkNotNull(includes);
    ProjectFilesystem projectFilesystem = repository.getFilesystem();
    if (!projectFilesystem.getRootPath().equals(filesystem.getRootPath())) {
      throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
          projectFilesystem.getRootPath(), filesystem.getRootPath()));
    }
    if (!isCacheComplete(includes, environment)) {
      state.invalidateAll();
      parseRawRulesInternal(
          ProjectBuildFileParser.getAllRulesInProject(
              buildFileParserFactory,
              includes,
              console,
              environment));
      allBuildFilesParsed = true;
    }
    return filterTargets(filter);
  }


  /**
   * Traverses the target graph starting from {@code roots} and returns the {@link BuildTarget}s
   * associated with the nodes for which the {@code filter} passes. See
   * {@link RuleJsonPredicates#matchBuildRuleType(BuildRuleType)} for an example of such a
   * {@link RuleJsonPredicate}.
   */
  public synchronized ImmutableSet<BuildTarget> targetsInProjectFromRoots(
      ImmutableSet<BuildTarget> roots,
      Iterable<String> defaultIncludes,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    ActionGraph actionGraph = parseBuildFilesForTargets(
        roots,
        defaultIncludes,
        eventBus,
        console,
        environment);

    return ImmutableSet.copyOf(
        FluentIterable.from(
            actionGraph.getNodes()).transform(
            new Function<BuildRule, BuildTarget>() {
              @Override
              public BuildTarget apply(BuildRule input) {
                return input.getBuildTarget();
                }
              }));
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
    if (LOG.isVerboseEnabled()) {
      LOG.verbose(
          "Parser watched event %s %s",
          event.kind(),
          repository.getFilesystem().createContextString(event));
    }

    if (repository.getFilesystem().isPathChangeEvent(event)) {
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
      state.invalidateDependents(path);

    } else {
      // Non-path change event, likely an overflow due to many change events: invalidate everything.
      LOG.debug("Parser invalidating entire cache on overflow.");
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
  private synchronized void invalidateContainingBuildFile(Path path) throws IOException {
    String packageBuildFilePath =
        buildFileTreeCache.get().getBasePathOfAncestorTarget(path).toString();
    state.invalidateDependents(
        repository.getFilesystem().getFileForRelativePath(
            packageBuildFilePath + '/' + BuckConstant.BUILD_RULES_FILE_NAME).toPath());
  }

  private boolean isPathCreateOrDeleteEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  /**
   * Always use Files created from absolute paths as they are returned from buck.py and must be
   * created from consistent paths to be looked up correctly in maps.
   * @param path A File to normalize.
   * @return An equivalent file constructed from a normalized, absolute path to the given File.
   */
  private Path normalize(Path path) {
    return repository.getFilesystem().resolve(path);
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
      public boolean isRelatedTo(BuckEvent event) {
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

  private class CachedState {

    /**
     * The build files that have been parsed and whose build rules are in
     * {@link #memoizedTargetNodes}.
     */
    private final ListMultimap<Path, Map<String, Object>> parsedBuildFiles;

    /**
     * We parse a build file in search for one particular rule; however, we also keep track of the
     * other rules that were also parsed from it.
     */
    private final Map<BuildTarget, TargetNode<?>> memoizedTargetNodes;

    /**
     * Environment used by build files. If the environment is changed, then build files need to be
     * reevaluated with the new environment, so the environment used when populating the rule cache
     * is stored between requests to parse build files and the cache is invalidated and build files
     * reevaluated if the environment changes.
     */
    @Nullable
    private ImmutableMap<String, String> cacheEnvironment;

    /**
     * Files included by build files. If the default includes are changed, then build files need to
     * be reevaluated with the new includes, so the includes used when populating the rule cache are
     * stored between requests to parse build files and the cache is invalidated and build files
     * reevaluated if the includes change.
     */
    @Nullable
    private List<String> cacheDefaultIncludes;

    private final Map<BuildTarget, Path> targetsToFile;

    public CachedState() {
      this.memoizedTargetNodes = Maps.newHashMap();
      this.parsedBuildFiles = ArrayListMultimap.create();
      this.targetsToFile = Maps.newHashMap();
    }

    public void invalidateAll() {
      parsedBuildFiles.clear();
      memoizedTargetNodes.clear();
      targetsToFile.clear();
    }

    /**
     * Invalidates the cached build rules if {@code environment} has changed since the last call.
     * If the cache is invalidated the new {@code environment} used to build the new cache is
     * stored.
     *
     * @param environment the environment to execute the build file in.
     * @return true if the cache was invalidated, false if the cache is still valid.
     */
    private synchronized boolean invalidateCacheOnEnvironmentChange(
        ImmutableMap<String, String> environment) {
      if (!Preconditions.checkNotNull(environment).equals(cacheEnvironment)) {
        LOG.debug("Parser invalidating entire cache on environment change.");
        invalidateCache();
        this.cacheEnvironment = environment;
        return true;
      }
      return false;
    }

    /**
     * Invalidates the cached build rules if {@code includes} have changed since the last call.
     * If the cache is invalidated the new {@code includes} used to build the new cache are stored.
     *
     * @param includes the files to include before executing the build file.
     * @return true if the cache was invalidated, false if the cache is still valid.
     */
    private synchronized boolean invalidateCacheOnIncludeChange(Iterable<String> includes) {
      List<String> includesList = Lists.newArrayList(Preconditions.checkNotNull(includes));
      if (!includesList.equals(this.cacheDefaultIncludes)) {
        LOG.debug("Parser invalidating entire cache on default include change.");
        invalidateCache();
        this.cacheDefaultIncludes = includesList;
        return true;
      }
      return false;
    }

    /**
     * Remove the targets and rules defined by {@code path} from the cache and recursively remove
     * the targets and rules defined by files that transitively include {@code path} from the cache.
     * @param path The File that has changed.
     */
    synchronized void invalidateDependents(Path path) {
      // Normalize path to ensure it hashes equally with map keys.
      path = normalize(path);

      if (parsedBuildFiles.containsKey(path)) {
        LOG.debug("Parser invalidating %s cache", path.toAbsolutePath());

        // Remove all targets defined by path from cache.
        for (Map<String, Object> rawRule : parsedBuildFiles.get(path)) {
          BuildTarget target = parseBuildTargetFromRawRule(rawRule);
          memoizedTargetNodes.remove(target);
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

    public boolean isParsed(Path buildFile) {
      return parsedBuildFiles.containsKey(normalize(buildFile));
    }

    public List<Map<String, Object>> getRawRules(Path buildFile) {
      return Preconditions.checkNotNull(parsedBuildFiles.get(normalize(buildFile)));
    }

    public void put(BuildTarget target, Map<String, Object> rawRules) {
      parsedBuildFiles.put(normalize(target.getBuildFilePath()), rawRules);

      targetsToFile.put(
          target,
          normalize(Paths.get((String) rawRules.get("buck.base_path")))
              .resolve("BUCK").toAbsolutePath());
    }

    public ImmutableSet<BuildTarget> filterTargets(RuleJsonPredicate filter) {
      ImmutableSet.Builder<BuildTarget> matchingTargets = ImmutableSet.builder();
      for (Map<String, Object> map : parsedBuildFiles.values()) {
        BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
        BuildTarget target = parseBuildTargetFromRawRule(map);
        if (filter.isMatch(map, buildRuleType, target)) {
          matchingTargets.add(target);
        }
      }

      return matchingTargets.build();
    }

    @Nullable
    public TargetNode<?> get(BuildTarget buildTarget) {
      // Fast path.
      TargetNode<?> toReturn = memoizedTargetNodes.get(buildTarget);
      if (toReturn != null) {
        return toReturn;
      }

      BuildTarget unflavored = buildTarget.getUnflavoredTarget();
      List<Map<String, Object>> rules = state.getRawRules(unflavored.getBuildFilePath());
      for (Map<String, Object> map : rules) {

        if (!buildTarget.getShortNameOnly().equals(map.get("name"))) {
          continue;
        }

        BuildRuleType buildRuleType = parseBuildRuleTypeFromRawRule(map);
        targetsToFile.put(
            unflavored,
            normalize(Paths.get((String) map.get("buck.base_path")))
                .resolve("BUCK").toAbsolutePath());

        Description<?> description = repository.getDescription(buildRuleType);
        if (description == null) {
          throw new HumanReadableException("Unrecognized rule %s while parsing %s%s.",
              buildRuleType,
              BuildTarget.BUILD_TARGET_PREFIX,
              unflavored.getBuildFilePath());
        }

        if ((description instanceof Flavored) &&
            !((Flavored) description).hasFlavor(buildTarget.getFlavor())) {
          throw new HumanReadableException("Unrecognized flavor in target %s while parsing %s%s.",
              buildTarget,
              BuildTarget.BUILD_TARGET_PREFIX,
              buildTarget.getBuildFilePath());
        }

        BuildRuleFactoryParams factoryParams = new BuildRuleFactoryParams(
            map,
            repository.getFilesystem(),
            buildTargetParser,
            // Although we store the rule by its unflavoured name, when we construct it, we need the
            // flavour.
            buildTarget,
            ruleKeyBuilderFactory);
        TargetNode<?> targetNode;
        try {
          targetNode = new TargetNode<>(description, factoryParams);
        } catch (NoSuchBuildTargetException e) {
          //
          throw new HumanReadableException(e);
        }

        TargetNode<?> existingTargetNode = memoizedTargetNodes.put(buildTarget, targetNode);
        if (existingTargetNode != null) {
          throw new HumanReadableException("Duplicate definition for " + unflavored);
        }

        // PMD considers it bad form to return while in a loop.
      }

      return memoizedTargetNodes.get(buildTarget);
    }
  }
}
