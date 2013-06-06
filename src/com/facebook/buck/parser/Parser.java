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

import com.facebook.buck.debug.Tracer;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilder;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;

public class Parser {

  private static final Logger logger = Logger.getLogger(Parser.class.getCanonicalName());

  private final BuildTargetParser buildTargetParser;

  /**
   * The build files that have been parsed and whose build rules are in {@link #knownBuildTargets}.
   */
  private final Set<File> parsedBuildFiles;

  /**
   * We parse a build file in search for one particular rule; however, we also keep track of the
   * other rules that were also parsed from it.
   */
  private final Map<BuildTarget, BuildRuleBuilder> knownBuildTargets;

  /**
   * If filterAllTargetsInProject is called, we cache the rule objects for subsequent calls with matching
   * rootPath and includes.
   */
  @Nullable
  private List<Map<String, Object>> rawRuleObjects;

  @Nullable
  private List<String> includesList;

  private final String absolutePathToProjectRoot;

  private final ProjectFilesystem projectFilesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final ProjectBuildFileParser buildFileParser;
  private BuildFileTree buildFiles;

  public Parser(ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes) {
    this(projectFilesystem,
        buildRuleTypes,
        BuildFileTree.constructBuildFileTree(projectFilesystem),
        new BuildTargetParser(projectFilesystem),
         /* knownBuildTargets */ Maps.<BuildTarget, BuildRuleBuilder>newHashMap(),
        new ProjectBuildFileParser());
  }

  @VisibleForTesting
  Parser(ProjectFilesystem projectFilesystem,
         KnownBuildRuleTypes buildRuleTypes,
         BuildFileTree buildFiles,
         BuildTargetParser buildTargetParser,
         Map<BuildTarget, BuildRuleBuilder> knownBuildTargets,
         ProjectBuildFileParser buildFileParser) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.buildFiles = Preconditions.checkNotNull(buildFiles);
    this.knownBuildTargets = Maps.newHashMap(Preconditions.checkNotNull(knownBuildTargets));
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
    this.buildFileParser = Preconditions.checkNotNull(buildFileParser);
    this.parsedBuildFiles = Sets.newHashSet();
    this.absolutePathToProjectRoot = projectFilesystem.getProjectRoot().getAbsolutePath();
  }

  public BuildTargetParser getBuildTargetParser() {
    return buildTargetParser;
  }

  public File getProjectRoot() {
    return projectFilesystem.getProjectRoot();
  }

  private boolean parsedAllBuildFiles() {
    return rawRuleObjects != null;
  }

  public DependencyGraph parseBuildFilesForTargets(
      Iterable<BuildTarget> buildTargets,
      Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    // Make sure that knownBuildTargets is initially populated with the BuildRuleBuilders for the
    // seed BuildTargets for the traversal.
    if (!parsedAllBuildFiles()) {
      Set<File> buildTargetFiles = Sets.newHashSet();
      for (BuildTarget buildTarget : buildTargets) {
        File buildFile = buildTarget.getBuildFile();
        boolean isNewElement = buildTargetFiles.add(buildFile);
        if (isNewElement) {
          parseBuildFile(buildFile, defaultIncludes);
        }
      }
    }

    DependencyGraph graph = findAllTransitiveDependencies(buildTargets, defaultIncludes);
    Tracer.addComment("All build files parsed and dependency graph constructed.");
    return graph;
  }

  /**
   * @param toExplore BuildTargets whose dependencies need to be explored.
   */
  @VisibleForTesting
  DependencyGraph findAllTransitiveDependencies(
      Iterable<BuildTarget> toExplore,
      final Iterable<String> defaultIncludes) {
    final Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    final MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();

    AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildTarget>() {
          @Override
          protected Iterator<BuildTarget> findChildren(BuildTarget buildTarget) {
            ParseContext parseContext = ParseContext.forBaseName(buildTarget.getBaseName());

            // Verify that the BuildTarget actually exists in the map of known BuildTargets
            // before trying to recurse through its children.
            if (!knownBuildTargets.containsKey(buildTarget)) {
              throw new HumanReadableException(
                  NoSuchBuildTargetException.createForMissingBuildRule(buildTarget, parseContext));
            }

            BuildRuleBuilder buildRuleBuilder = knownBuildTargets.get(buildTarget);

            Set<BuildTarget> deps = Sets.newHashSet();
            for (String dep : buildRuleBuilder.getDeps()) {
              try {
                BuildTarget buildTargetForDep = buildTargetParser.parse(dep, parseContext);
                if (!knownBuildTargets.containsKey(buildTargetForDep)) {
                  parseBuildFileContainingTarget(buildTargetForDep, defaultIncludes);
                }
                deps.add(buildTargetForDep);
              } catch (NoSuchBuildTargetException e) {
                throw new HumanReadableException(e);
              } catch (IOException e) {
                Throwables.propagate(e);
              }
            }

            return deps.iterator();
          }

          @Override
          protected void onNodeExplored(BuildTarget buildTarget) {
            BuildRuleBuilder builderForTarget = knownBuildTargets.get(buildTarget);
            BuildRule buildRule = builderForTarget.build(buildRuleIndex);

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

            buildRuleIndex.put(buildTarget.getFullyQualifiedName(), buildRule);
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
      BuildTarget buildTarget, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    if (parsedAllBuildFiles()) {
      // In this case, all of the build rules should have been loaded into the knownBuildTargets
      // Map before this method was invoked. Therefore, there should not be any more build files to
      // parse. This must be the result of traversing a non-existent dep in a build rule, so an
      // error is reported to the user. Unfortunately, the source of the build file where the
      // non-existent rule was declared is not known at this point, which is why it is not included
      // in the error message.
      throw new HumanReadableException("No such build target: %s.", buildTarget);
    }

    File buildFile = buildTarget.getBuildFile();
    if (parsedBuildFiles.contains(buildFile)) {
      throw new HumanReadableException(
          "The build file that should contain %s has already been parsed (%s), " +
              "but %s was not found. Please make sure that %s is defined in %s.",
          buildTarget,
          buildFile,
          buildTarget,
          buildTarget,
          buildFile);
    }

    parseBuildFile(buildFile, defaultIncludes);
  }

  private void parseBuildFile(File buildFile, Iterable<String> defaultIncludes)
      throws IOException, NoSuchBuildTargetException {
    if (parsedBuildFiles.contains(buildFile) || parsedAllBuildFiles()) {
      return; // Use cached rules.
    }
    logger.info(String.format("Parsing %s file: %s",
        BuckConstant.BUILD_RULES_FILE_NAME,
        buildFile));
    List<Map<String, Object>> rules = buildFileParser.getAllRules(
        absolutePathToProjectRoot, Optional.of(buildFile.getPath()), defaultIncludes);
    parseRawRulesInternal(rules, null /* filter */, buildFile);
    parsedBuildFiles.add(buildFile);
  }

  @VisibleForTesting
  @Nullable
  List<BuildTarget> parseRawRulesInternal(List<Map<String, Object>> rules,
      @Nullable RawRulePredicate filter,
      @Nullable File source) throws NoSuchBuildTargetException {
    List<BuildTarget> matchingTargets = (filter == null) ? null : Lists.<BuildTarget>newArrayList();

    for (Map<String, Object> map : rules) {
      String type = (String)map.get("type");
      BuildRuleType buildRuleType = buildRuleTypes.getBuildRuleType(type);

      String basePath = (String)map.get("buck_base_path");

      File sourceOfBuildTarget;
      if (source == null) {
        String relativePathToBuildFile = !basePath.isEmpty()
            ? basePath + "/" + BuckConstant.BUILD_RULES_FILE_NAME
            : BuckConstant.BUILD_RULES_FILE_NAME;
        sourceOfBuildTarget = new File(projectFilesystem.getProjectRoot(), relativePathToBuildFile);
      } else {
        sourceOfBuildTarget = source;
      }

      BuildRuleFactory factory = buildRuleTypes.getFactory(buildRuleType);
      if (factory == null) {
        throw new HumanReadableException("Unrecognized rule %s while parsing %s.",
            type,
            sourceOfBuildTarget);
      }

      String name = (String)map.get("name");
      BuildTarget target = new BuildTarget(sourceOfBuildTarget, "//" + basePath, name);

      if (filter != null && filter.isMatch(map, buildRuleType, target)) {
        matchingTargets.add(target);
      }

      BuildRuleBuilder buildRuleBuilder = factory.newInstance(new BuildRuleFactoryParams(
          map,
          System.err, // TODO(simons): Injecting a Console instance turns out to be a nightmare.
          projectFilesystem,
          buildFiles,
          buildTargetParser,
          target));
      Object existingRule = knownBuildTargets.put(target, buildRuleBuilder);
      if (existingRule != null) {
        throw new RuntimeException("Duplicate definition for " + target.getFullyQualifiedName());
      }
    }

    return matchingTargets;
  }

  /**
   * Populates the collection of known build targets that this Parser will use to construct a
   * dependency graph using all build files inside the given project root and returns an optionally
   * filtered set of build targets.
   *
   *
   * @param filesystem The project filesystem.
   * @param includes A list of python files that should be imported by each build file.
   *
   * @param filter if specified, applied to each rule in rules. All matching rules will be included
   *     in the List returned by this method. If filter is null, then this method returns null.
   *
   * @return The build targets in the project filtered by the given filter.
   */
  public List<BuildTarget> filterAllTargetsInProject(ProjectFilesystem filesystem,
                                                     Iterable<String> includes,
                                                     @Nullable RawRulePredicate filter)
      throws IOException, NoSuchBuildTargetException {
    Preconditions.checkNotNull(filesystem);
    Preconditions.checkNotNull(includes);
    if (!projectFilesystem.getProjectRoot().equals(filesystem.getProjectRoot())) {
      throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
          projectFilesystem.getProjectRoot(), filesystem.getProjectRoot()));
    }
    List<String> includesList = Lists.newArrayList(includes);
    if (!parsedAllBuildFiles() || !includesList.equals(this.includesList)) {
      this.includesList = includesList;
      rawRuleObjects = buildFileParser.getAllRulesInProject(filesystem.getProjectRoot(), includes);
    }
    knownBuildTargets.clear();
    return parseRawRulesInternal(rawRuleObjects, filter, null /* source */);
  }

  /**
   * Called when file change events are posted to the file change EventBus to invalidate cached
   * build rules if required.
   */
  @Subscribe
  public synchronized void onFileSystemChange(WatchEvent<?> event) {
    if (projectFilesystem.isPathChangeEvent(event)) {
      // TODO(user): Track the files imported by build files
      // Currently we just assume ".java" files are the only ones that can't affect build files.
      final String SRC_EXTENSION = ".java";
      Path path = (Path) event.context();
      if (path.toString().endsWith(SRC_EXTENSION)) {
        return;
      }
    }
    // TODO(user): invalidate affected build files, rather than nuking all rules completely.
    buildFiles = BuildFileTree.constructBuildFileTree(projectFilesystem);
    parsedBuildFiles.clear();
    knownBuildTargets.clear();
    rawRuleObjects = null;
  }
}
