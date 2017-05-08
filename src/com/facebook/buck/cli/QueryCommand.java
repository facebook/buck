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

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class QueryCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(QueryCommand.class);

  /**
   * Example usage:
   *
   * <pre>
   * buck query "allpaths('//path/to:target', '//path/to:other')" --dot > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot", usage = "Print result as Dot graph")
  private boolean generateDotOutput;

  @Option(name = "--bfs", usage = "Sort the dot output in bfs order")
  private boolean generateBFSOutput;

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Option(
    name = "--output-attributes",
    usage =
        "List of attributes to output, --output-attributes attr1 att2 ... attrN. "
            + "Attributes can be regular expressions. ",
    handler = StringSetOptionHandler.class
  )
  @SuppressFieldNotInitialized
  @VisibleForTesting
  Supplier<ImmutableSet<String>> outputAttributes;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  public boolean shouldGenerateBFSOutput() {
    return generateBFSOutput;
  }

  public boolean shouldOutputAttributes() {
    return !outputAttributes.get().isEmpty();
  }

  @Argument private List<String> arguments = new ArrayList<>();

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (arguments.isEmpty()) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe("Must specify at least the query expression"));
      return 1;
    }

    try (CommandThreadManager pool =
            new CommandThreadManager("Query", getConcurrencyLimit(params.getBuckConfig()));
        PerBuildState parserState =
            new PerBuildState(
                params.getParser(),
                params.getBuckEventBus(),
                pool.getExecutor(),
                params.getCell(),
                getEnableParserProfiling(),
                SpeculativeParsing.of(true))) {
      BuckQueryEnvironment env =
          BuckQueryEnvironment.from(params, parserState, getEnableParserProfiling());
      ListeningExecutorService executor = pool.getExecutor();
      return formatAndRunQuery(params, env, executor);
    } catch (QueryException | BuildFileParseException e) {
      throw new HumanReadableException(e);
    }
  }

  @VisibleForTesting
  int formatAndRunQuery(
      CommandRunnerParams params, BuckQueryEnvironment env, ListeningExecutorService executor)
      throws IOException, InterruptedException, QueryException {
    String queryFormat = arguments.get(0);
    List<String> formatArgs = arguments.subList(1, arguments.size());
    if (queryFormat.contains("%Ss")) {
      return runSingleQueryWithSet(params, env, executor, queryFormat, formatArgs);
    } else if (queryFormat.contains("%s")) {
      return runMultipleQuery(
          params, env, executor, queryFormat, formatArgs, shouldGenerateJsonOutput());
    } else if (formatArgs.size() > 0) {
      throw new HumanReadableException(
          "Must not specify format arguments without a %s or %Ss in the query");
    } else {
      return runSingleQuery(params, env, executor, queryFormat);
    }
  }

  /** Format and evaluate the query using list substitution */
  int runSingleQueryWithSet(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      ListeningExecutorService executor,
      String queryFormat,
      List<String> formatArgs)
      throws InterruptedException, QueryException, IOException {
    String argsList =
        Joiner.on(' ').join(Iterables.transform(formatArgs, input -> "'" + input + "'"));
    String setRepresentation = "set(" + argsList + ")";
    String formattedQuery = queryFormat.replace("%Ss", setRepresentation);
    return runSingleQuery(params, env, executor, formattedQuery);
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage: buck query <query format>
   * <input1> <input2> <...> <inputN>
   */
  static int runMultipleQuery(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      ListeningExecutorService executor,
      String queryFormat,
      List<String> inputsFormattedAsBuildTargets,
      boolean generateJsonOutput)
      throws IOException, InterruptedException, QueryException {
    if (inputsFormattedAsBuildTargets.isEmpty()) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  "Specify one or more input targets after the query expression format"));
      return 1;
    }

    // Do an initial pass over the query arguments and parse them into their expressions so we can
    // preload all the target patterns from every argument in one go, as doing them one-by-one is
    // really inefficient.
    Set<String> targetLiterals = new LinkedHashSet<>();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      QueryExpression expr = QueryExpression.parse(query, env);
      expr.collectTargetPatterns(targetLiterals);
    }
    env.preloadTargetPatterns(targetLiterals, executor);

    // Now execute the query on the arguments one-by-one.
    TreeMultimap<String, QueryTarget> queryResultMap = TreeMultimap.create();
    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      ImmutableSet<QueryTarget> queryResult = env.evaluateQuery(query, executor);
      queryResultMap.putAll(input, queryResult);
    }

    LOG.debug("Printing out the following targets: " + queryResultMap);
    if (generateJsonOutput) {
      CommandHelper.printJSON(params, queryResultMap);
    } else {
      CommandHelper.printToConsole(params, queryResultMap);
    }
    return 0;
  }

  int runSingleQuery(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      ListeningExecutorService executor,
      String query)
      throws IOException, InterruptedException, QueryException {
    ImmutableSet<QueryTarget> queryResult = env.evaluateQuery(query, executor);

    LOG.debug("Printing out the following targets: " + queryResult);
    if (shouldOutputAttributes()) {
      collectAndPrintAttributes(params, env, queryResult);
    } else if (shouldGenerateDotOutput()) {
      printDotOutput(params, env, queryResult);
    } else if (shouldGenerateJsonOutput()) {
      CommandHelper.printJSON(params, queryResult);
    } else {
      CommandHelper.printToConsole(params, queryResult);
    }
    return 0;
  }

  private void printDotOutput(
      CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryTarget> queryResult)
      throws IOException, QueryException {
    Dot.writeSubgraphOutput(
        env.getTargetGraph(),
        "result_graph",
        env.getNodesFromQueryTargets(queryResult),
        targetNode -> "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"",
        targetNode -> Description.getBuildRuleType(targetNode.getDescription()).getName(),
        params.getConsole().getStdOut(),
        shouldGenerateBFSOutput());
  }

  private void collectAndPrintAttributes(
      CommandRunnerParams params, BuckQueryEnvironment env, Set<QueryTarget> queryResult)
      throws QueryException {
    PatternsMatcher patternsMatcher = new PatternsMatcher(outputAttributes.get());
    SortedMap<String, SortedMap<String, Object>> result = new TreeMap<>();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }
      TargetNode<?, ?> node = env.getNode(target);
      try {
        SortedMap<String, Object> sortedTargetRule =
            params.getParser().getRawTargetNode(env.getParserState(), params.getCell(), node);
        if (sortedTargetRule == null) {
          params
              .getConsole()
              .printErrorText(
                  "unable to find rule for target "
                      + node.getBuildTarget().getFullyQualifiedName());
          continue;
        }
        SortedMap<String, Object> attributes = new TreeMap<>();
        if (patternsMatcher.hasPatterns()) {
          for (String key : sortedTargetRule.keySet()) {
            String snakeCaseKey = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, key);
            if (patternsMatcher.matches(snakeCaseKey)) {
              attributes.put(snakeCaseKey, sortedTargetRule.get(key));
            }
          }
        }

        result.put(
            node.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName(), attributes);
      } catch (BuildFileParseException e) {
        params
            .getConsole()
            .printErrorText(
                "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
        continue;
      }
    }
    StringWriter stringWriter = new StringWriter();
    try {
      ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, result);
    } catch (IOException e) {
      // Shouldn't be possible while writing to a StringWriter...
      throw new RuntimeException(e);
    }
    String output = stringWriter.getBuffer().toString();
    params.getConsole().getStdOut().println(output);
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the target nodes graph";
  }

  public static String getEscapedArgumentsListAsString(List<String> arguments) {
    return Joiner.on(" ").join(Lists.transform(arguments, arg -> "'" + arg + "'"));
  }

  static String getAuditDependenciesQueryFormat(boolean isTransitive, boolean includeTests) {
    StringBuilder queryBuilder = new StringBuilder();
    queryBuilder.append(isTransitive ? "deps('%s') " : "deps('%s', 1) ");
    if (includeTests) {
      queryBuilder.append(isTransitive ? "union deps(testsof(deps('%s')))" : "union testsof('%s')");
    }
    queryBuilder.append(" except set('%s')");
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit dependencies'. */
  static String buildAuditDependenciesQueryExpression(
      List<String> arguments, boolean isTransitive, boolean includeTests, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query ");
    queryBuilder.append("\"" + getAuditDependenciesQueryFormat(isTransitive, includeTests) + "\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit tests'. */
  static String buildAuditTestsQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"testsof('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }

  /** @return the equivalent 'buck query' call to 'buck audit owner'. */
  static String buildAuditOwnerQueryExpression(List<String> arguments, boolean jsonOutput) {
    StringBuilder queryBuilder = new StringBuilder("buck query \"owner('%s')\" ");
    queryBuilder.append(getEscapedArgumentsListAsString(arguments));
    if (jsonOutput) {
      queryBuilder.append(" --json");
    }
    return queryBuilder.toString();
  }
}
