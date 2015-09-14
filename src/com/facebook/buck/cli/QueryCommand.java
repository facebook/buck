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

import com.facebook.buck.graph.Dot;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetNode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.TreeMultimap;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;

public class QueryCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(QueryCommand.class);

  /**
   * Example usage:
   * <pre>
   * buck query "allpaths('//path/to:target', '//path/to:other')" --dot > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot",
      usage = "Print result as Dot graph")
  private boolean generateDotOutput;

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  @Option(name = "--output-attributes",
      usage = "List of attributes to output, --output-attributes attr1 att2 ... attrN --other-opt",
      handler = StringSetOptionHandler.class)
  @SuppressFieldNotInitialized
  private Supplier<ImmutableSet<String>> outputAttributes;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  public boolean shouldOutputAttributes() {
    return !outputAttributes.get().isEmpty();
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (arguments.isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least the query expression");
      return 1;
    }

    // We're not using any of Bazel's settings.
    Set<QueryEnvironment.Setting> settings = new HashSet<>();
    BuckQueryEnvironment env = new BuckQueryEnvironment(params, settings, getEnableProfiling());

    try {
      String queryFormat = arguments.remove(0);
      if (queryFormat.contains("%s")) {
        return runMultipleQuery(params, env, queryFormat, arguments, shouldGenerateJsonOutput());
      } else {
        return runSingleQuery(params, env, queryFormat);
      }
    } catch (RuntimeException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      if (e.getCause() instanceof QueryException) {
        params.getConsole().printBuildFailureWithoutStacktrace(e);
        return 1;
      }
      throw e;
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage:
   *   buck query <query format> <input1> <input2> <...> <inputN>
   */
  static int runMultipleQuery(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      String queryFormat,
      List<String> inputsFormattedAsBuildTargets,
      boolean generateJsonOutput)
      throws IOException, InterruptedException, QueryException {
    if (inputsFormattedAsBuildTargets.isEmpty()) {
      params.getConsole().printBuildFailure(
          "Specify one or more input targets after the query expression format");
      return 1;
    }

    TreeMultimap<String, QueryTarget> queryResultMap = TreeMultimap.create();

    for (String input : inputsFormattedAsBuildTargets) {
      String query = queryFormat.replace("%s", input);
      Set<QueryTarget> queryResult = env.evaluateQuery(query);
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

  int runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env, String query)
      throws IOException, InterruptedException, QueryException {
    Set<QueryTarget> queryResult = env.evaluateQuery(query);

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
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult)
      throws IOException, QueryException, InterruptedException {
    Dot.writeSubgraphOutput(
        env.getTargetGraph(),
        "result_graph",
        env.getNodesFromQueryTargets(queryResult),
        new Function<TargetNode<?>, String>() {
          @Override
          public String apply(TargetNode<?> targetNode) {
            return "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"";
          }
        },
        params.getConsole().getStdOut());
  }

  private void collectAndPrintAttributes(
      CommandRunnerParams params,
      BuckQueryEnvironment env,
      Set<QueryTarget> queryResult)
      throws InterruptedException, IOException, QueryException {
    ParserConfig parserConfig = new ParserConfig(params.getBuckConfig());
    SortedMap<String, SortedMap<String, Object>> result = Maps.newTreeMap();
    for (QueryTarget target : queryResult) {
      if (!(target instanceof QueryBuildTarget)) {
        continue;
      }
      TargetNode<?> node = env.getNode(target);
      try {
        SortedMap<String, Object> sortedTargetRule =
            CommandHelper.getBuildTargetRules(params, parserConfig, node);
        if (sortedTargetRule == null) {
          params.getConsole().printErrorText(
              "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
          continue;
        }
        SortedMap<String, Object> attributes = Maps.newTreeMap();
        for (String attribute : outputAttributes.get()) {
          if (sortedTargetRule.containsKey(attribute)) {
            attributes.put(attribute, sortedTargetRule.get(attribute));
          }
        }
        result.put(
            node.getBuildTarget().getUnflavoredBuildTarget().getFullyQualifiedName(),
            attributes);
      } catch (BuildFileParseException e) {
        params.getConsole().printErrorText(
            "unable to find rule for target " + node.getBuildTarget().getFullyQualifiedName());
        continue;
      }
    }
    StringWriter stringWriter = new StringWriter();
    try {
      params.getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(stringWriter, result);
    } catch (IOException e) {
      // Shouldn't be possible while writing to a StringWriter...
      throw Throwables.propagate(e);
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
    return Joiner.on(" ").join(
        Lists.transform(
            arguments,
            new Function<String, String>() {
              @Override
              public String apply(String arg) {
                return "'" + arg + "'";
              }
            }));
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
      List<String> arguments,
      boolean isTransitive,
      boolean includeTests,
      boolean jsonOutput) {
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
}
