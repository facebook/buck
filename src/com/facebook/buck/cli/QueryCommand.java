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
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.TreeMultimap;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.MissingFormatArgumentException;
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

  public List<String> getMultipleQueryInputsFormattedAsBuildTargets(BuckConfig buckConfig) {
    // Don't consider the first argument as input because it is the query expression format.
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(
        arguments.subList(1, arguments.size()));
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (getArguments().isEmpty()) {
      params.getConsole().printBuildFailure("Must specify at least a the query expression");
      return 1;
    }

    // We're not using any of Bazel's settings.
    Set<QueryEnvironment.Setting> settings = new HashSet<>();
    BuckQueryEnvironment env = new BuckQueryEnvironment(params, settings, getEnableProfiling());

    String query = getArguments().get(0);
    if (query.contains("%s")) {
      return runMultipleQuery(params, env);
    } else {
      return runSingleQuery(params, env);
    }
  }

  /**
   * Evaluate multiple queries in a single `buck query` run. Usage:
   *   buck query <query format> <input1> <input2> <...> <inputN>
   */
  private int runMultipleQuery(CommandRunnerParams params, BuckQueryEnvironment env)
      throws IOException, InterruptedException {
    if (getArguments().size() < 2) {
      params.getConsole().printBuildFailure(
          "Specify one or more input targets after the query expression format");
      return 1;
    }

    try {
      String queryFormat = getArguments().get(0);
      TreeMultimap<BuildTarget, BuildTarget> queryResultMap = TreeMultimap.create();

      for (String input : getMultipleQueryInputsFormattedAsBuildTargets(params.getBuckConfig())) {
        BuildTarget target = BuildTargetParser.INSTANCE.parse(
            input,
            BuildTargetPatternParser.fullyQualified());
        String query = String.format(queryFormat, input);
        Set<BuildTarget> queryResult = env.evaluateQuery(query);
        queryResultMap.putAll(target, queryResult);
      }

      LOG.debug("Printing out the following targets: " + queryResultMap);
      if (shouldGenerateJsonOutput()) {
        CommandHelper.printJSON(params, queryResultMap);
      } else {
        CommandHelper.printToConsole(params, queryResultMap);
      }
    } catch (QueryException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    } catch (MissingFormatArgumentException e) {
      params.getConsole().printBuildFailure(
          "The query expression should contain only one format specifier");
    }
    return 0;
  }

  private int runSingleQuery(CommandRunnerParams params, BuckQueryEnvironment env)
      throws IOException, InterruptedException {
    try {
      String query = getArguments().get(0);
      Set<BuildTarget> queryResult = env.evaluateQuery(query);

      LOG.debug("Printing out the following targets: " + queryResult);
      if (shouldGenerateJsonOutput()) {
        CommandHelper.printJSON(params, queryResult);
      } else {
        CommandHelper.printToConsole(params, queryResult);
      }
    } catch (QueryException e) {
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

}
