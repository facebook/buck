/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicates;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.kernel.Traversal;
import org.neo4j.kernel.Uniqueness;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public class QueryCommand extends AbstractCommandRunner<QueryCommandOptions> {
  /**
   * Path (relative to project root) to the scratch directory where the neo4j graph database will be
   * written.
   */
  static final java.nio.file.Path DB_PATH = Paths.get(BuckConstant.BIN_DIR, "querydb");

  /**
   * Right now there is only one kind of relationship we store in our dependency
   * graph: depends-on.
   */
  static enum QueryRelType implements RelationshipType {
    DEP,
  }

  public QueryCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  QueryCommandOptions createOptions(BuckConfig buckConfig) {
    return new QueryCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(QueryCommandOptions options)
      throws IOException, HumanReadableException {
    if (options.getArguments().size() != 1) {
      getStdErr().printf(
          "buck query expects 1 argument, but received %d.\n",
          options.getArguments().size());
      return 1;
    }

    DependencyQuery query = DependencyQuery.parseQueryString(options.getArguments().get(0),
        options.getCommandLineBuildTargetNormalizer());

    PartialGraph graph;
    try {
      graph = PartialGraph.createPartialGraph(
          RawRulePredicates.matchName(query.getTarget()),
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } catch (BuildFileParseException | BuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // String output has no trailing newline.
    String output = executeQuery(graph, query);
    getStdOut().println(output);
    return 0;
  }

  /**
   * Given a parsed query and a PartialGraph, executes the dependency query and returns
   * output fit for the terminal. Internally this method creates, populates, and queries a neo4j
   * database, but this should be transparent to the caller and the implementation could be
   * changed in the future.
   *
   * @param partialGraph dependency graph including the target we are querying.
   * @param query dependency query we want to run.
   * @return String output to be printed with no trailing newline.
   * @throws HumanReadableException on unrecognized build targets.
   */
  @VisibleForTesting
  String executeQuery(PartialGraph partialGraph, DependencyQuery query)
      throws HumanReadableException {
    // Clear the on-disk database and rebuild each time, this could be inefficient.
    clearDbPath();

    String pathToDatabaseDirectory = getProjectFilesystem().getFileForRelativePath(DB_PATH)
        .getAbsolutePath();
    GraphDatabaseService graphDb = new GraphDatabaseFactory().newEmbeddedDatabase(
        pathToDatabaseDirectory);
    registerShutdownHook(graphDb);

    String output;
    try (Transaction tx = graphDb.beginTx()) {
      Map<String, Node> nameNodeMap = populateNeo4j(graphDb, partialGraph);

      if (!nameNodeMap.containsKey(query.getTarget())) {
        throw new HumanReadableException(
            String.format("Unknown build target: %s.", query.getTarget()));
      }

      if (query.getSource().isPresent()) {
        // Process a path query.
        String presentSource = query.getSource().get();
        if (!nameNodeMap.containsKey(presentSource)) {
          throw new HumanReadableException(
              String.format("Unknown build target: %s.", presentSource));
        }
        output = traversePaths(
            nameNodeMap.get(query.getTarget()),
            nameNodeMap.get(presentSource),
            query.getDepth(),
            /* shortestPathOnly */ true);
      } else {
        // Process a dependency query.
        output = traverseDependencies(
            nameNodeMap.get(query.getTarget()),
            query.getDepth());
      }

      tx.success();
    } finally {
      // Closing a transaction does not shutdown the database, so we make sure to do it here
      // despite the try-with-resources.
      graphDb.shutdown();
    }

    return output;
  }

  /**
   * Copies the Buck graph into the neo4j database.
   *
   * @param graphDb graph database that has been initialized.
   * @param partialGraph that we want to load into neo4j.
   * @return map from fully qualified names to nodes in neo4j.
   */
  private Map<String, Node> populateNeo4j(
      GraphDatabaseService graphDb,
      PartialGraph partialGraph) {
    SortedMap<String, Node> nameNodeMap = Maps.newTreeMap();

    DependencyGraph dependencyGraph = partialGraph.getDependencyGraph();

    // Add nodes, which are BuildRules named by their BuildTarget.
    for (BuildRule rule : dependencyGraph.getNodes()) {
      Node node = graphDb.createNode();
      String nodeName = rule.getFullyQualifiedName();
      node.setProperty("name", nodeName);
      nameNodeMap.put(nodeName, node);
    }

    // Add edges.
    for (BuildRule rule : dependencyGraph.getNodes()) {
      Node source = nameNodeMap.get(rule.getFullyQualifiedName());
      for (BuildRule dep : rule.getDeps()) {
        Node sink = nameNodeMap.get(dep.getFullyQualifiedName());
        source.createRelationshipTo(sink, QueryRelType.DEP);
      }
    }

    return nameNodeMap;
  }

  /**
   * Traverse the graph tracking all of the transitive dependencies for a target node
   * up to a certain depth.
   *
   * @param target node we find dependencies of.
   * @param depth number of steps we trace backwards in a BFS.
   * @return String output of all of the dependencies. Does not include trailing newline.
   */
  private String traverseDependencies(Node target, Optional<Integer> depth) {
    Evaluator eval = (depth.isPresent()) ? Evaluators.toDepth(depth.get()) : Evaluators.all();

    Iterable<Node> dependencyNodes = Traversal.description()
        .breadthFirst()
        .relationships(QueryRelType.DEP, Direction.OUTGOING)
        .evaluator(eval)
        .traverse(target)
        .nodes();

    List<String> nodeNames = Lists.newArrayList();
    for (Node dep : dependencyNodes) {
      nodeNames.add((String)dep.getProperty("name"));
    }
    return Joiner.on('\n').join(nodeNames);
  }

  /**
   * Traverse all or one of the dependency paths from a target back to one of its dependencies.
   *
   * @param target  node which has dependencies.
   * @param source  node which is depended upon.
   * @param depth   number of steps we trace backwards in a BFS. If absent we do not limit the
   *                depth and perform a full traversal.
   * @param shortestPathOnly dictates whether we find all paths or just the shortest.
   * @return output of all of the dependency paths. Does not includes trailing newline.
   */
  private String traversePaths(Node target,
      Node source, Optional<Integer> depth,
      boolean shortestPathOnly) {
    List<String> outputLines = Lists.newArrayList();
    Evaluator eval = (depth.isPresent()) ? Evaluators.toDepth(depth.get()) : Evaluators.all();
    Uniqueness uniq = shortestPathOnly ? Uniqueness.NODE_GLOBAL : Uniqueness.NONE;

    Iterable<Path> dependencyPaths = Traversal.description()
        .breadthFirst()
        .relationships(QueryRelType.DEP, Direction.OUTGOING)
        .evaluator(eval)
        .evaluator(Evaluators.includeWhereEndNodeIs(source))
        .uniqueness(uniq)
        .traverse(target);
    for (Path path : dependencyPaths) {
      Iterable<String> nodeNames = Iterables.transform(
          path.nodes(),
          new Function<Node, String>() {
            @Override
            public String apply(Node node) {
              return (String) node.getProperty("name");
            }
          });
      outputLines.add(Joiner.on(" -> ").join(nodeNames));
    }
    return Joiner.on('\n').join(outputLines);
  }


  /**
   * Registers a shutdown hook for the Neo4j instance so that it shuts down nicely
   * when the VM exits (even if you "Ctrl-C" the running application).
   *
   * @param graphDb database service to shut down.
   */
  private static void registerShutdownHook(final GraphDatabaseService graphDb) {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        graphDb.shutdown();
      }
    });
  }

  private void clearDbPath() {
    try {
      getProjectFilesystem().rmdir(DB_PATH);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  String getUsageIntro() {
    return "performs a neo4j query on the dependency graph";
  }
}
