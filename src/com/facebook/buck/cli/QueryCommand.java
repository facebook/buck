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
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
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

    if (getArguments().size() != 1) {
      params.getConsole().printBuildFailure("Specify a single argument: the query expression");
      return 1;
    }

    try {
      // We're not using any of Bazel's settings.
      Set<QueryEnvironment.Setting> settings = new HashSet<>();
      BuckQueryEnvironment env = new BuckQueryEnvironment(params, settings, getEnableProfiling());

      String query = getArguments().get(0);
      Set<BuildTarget> queryResult = env.evaluateQuery(query);

      LOG.debug("Printing out the following targets: " + queryResult);
      if (shouldGenerateJsonOutput()) {
        printJSON(params, queryResult);
      } else {
        printToConsole(params, queryResult);
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

  private void printJSON(
      CommandRunnerParams params,
      Set<BuildTarget> targets) throws IOException {
    Set<String> targetsNames = ImmutableSet.copyOf(
        Collections2.transform(
            targets,
            new Function<BuildTarget, String>() {
              @Override
              public String apply(BuildTarget input) {
                return Preconditions.checkNotNull(input.getFullyQualifiedName());
              }
            }));
    params.getObjectMapper().writeValue(
      params.getConsole().getStdOut(),
      targetsNames);
  }

  private void printToConsole(
      CommandRunnerParams params,
      Set<BuildTarget> targets) {
    for (BuildTarget target : targets) {
      params.getConsole().getStdOut().println(target.getFullyQualifiedName());
    }
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to query information about the target nodes graph";
  }

}
