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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.TreeMultimap;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueryCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(QueryCommand.class);

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
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

    String queryFormat = arguments.remove(0);
    if (queryFormat.contains("%s")) {
      return runMultipleQuery(params, env, queryFormat, arguments, shouldGenerateJsonOutput());
    } else {
      return runSingleQuery(params, env, queryFormat);
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
      throws IOException, InterruptedException {
    if (inputsFormattedAsBuildTargets.isEmpty()) {
      params.getConsole().printBuildFailure(
          "Specify one or more input targets after the query expression format");
      return 1;
    }

    try {
      TreeMultimap<String, BuildTarget> queryResultMap = TreeMultimap.create();

      for (String input : inputsFormattedAsBuildTargets) {
        String query = queryFormat.replace("%s", input);
        Set<BuildTarget> queryResult = env.evaluateQuery(query);
        queryResultMap.putAll(input, queryResult);
      }

      LOG.debug("Printing out the following targets: " + queryResultMap);
      if (generateJsonOutput) {
        CommandHelper.printJSON(params, queryResultMap);
      } else {
        CommandHelper.printToConsole(params, queryResultMap);
      }
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }
    return 0;
  }

  int runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env, String query)
      throws IOException, InterruptedException {
    try {
      Set<BuildTarget> queryResult = env.evaluateQuery(query);

      LOG.debug("Printing out the following targets: " + queryResult);
      if (shouldGenerateJsonOutput()) {
        CommandHelper.printJSON(params, queryResult);
      } else {
        CommandHelper.printToConsole(params, queryResult);
      }
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }
    return 0;
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
