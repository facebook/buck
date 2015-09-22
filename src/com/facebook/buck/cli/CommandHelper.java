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

package com.facebook.buck.cli;

import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import javax.annotation.Nullable;

public abstract class CommandHelper {

  public static void printJSON(
      CommandRunnerParams params,
      Multimap<String, QueryTarget> targetsAndResults)
      throws IOException {
    Multimap<String, String> targetsAndResultsNames =
        Multimaps.transformValues(
            targetsAndResults,
            new Function<QueryTarget, String>() {
              @Override
              public String apply(QueryTarget input) {
                return Preconditions.checkNotNull(input.toString());
              }
            });
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsAndResultsNames.asMap());
  }

  public static void printJSON(
      CommandRunnerParams params,
      Set<QueryTarget> targets) throws IOException {
    Set<String> targetsNames = ImmutableSet.copyOf(
        Collections2.transform(
            targets,
            new Function<QueryTarget, String>() {
              @Override
              public String apply(QueryTarget input) {
                return Preconditions.checkNotNull(input.toString());
              }
            }));
    params.getObjectMapper().writeValue(
        params.getConsole().getStdOut(),
        targetsNames);
  }

  public static void printToConsole(
      CommandRunnerParams params,
      Multimap<String, QueryTarget> targetsAndDependencies) {
    for (QueryTarget target : ImmutableSortedSet.copyOf(targetsAndDependencies.values())) {
      params.getConsole().getStdOut().println(target);
    }
  }

  public static void printToConsole(
      CommandRunnerParams params,
      Set<QueryTarget> targets) {
    for (QueryTarget target : targets) {
      params.getConsole().getStdOut().println(target);
    }
  }

  @Nullable
  public static SortedMap<String, Object> getBuildTargetRules(
      CommandRunnerParams params,
      ParserConfig parserConfig,
      TargetNode<?> targetNode)
      throws BuildFileParseException, InterruptedException, IOException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    List<Map<String, Object>> rules;
    try {
      Path buildFile = params.getCell().getAbsolutePathToBuildFile(buildTarget);
      rules = params.getParser().parseBuildFile(
          buildFile,
          parserConfig,
          params.getEnvironment(),
          params.getConsole(),
          params.getBuckEventBus());
    } catch (BuildTargetException e) {
      return null;
    }

    // Find the build rule information that corresponds to this build buildTarget.
    Map<String, Object> targetRule = null;
    for (Map<String, Object> rule : rules) {
      String name = (String) Preconditions.checkNotNull(rule.get("name"));
      if (name.equals(buildTarget.getShortName())) {
        targetRule = rule;
        break;
      }
    }

    if (targetRule == null) {
      return null;
    }

    targetRule.put(
        "buck.direct_dependencies",
        ImmutableList.copyOf((Iterables.transform(
            targetNode.getDeps(),
            Functions.toStringFunction()))));

    // Sort the rule items, both so we have a stable order for unit tests and
    // to improve readability of the output.
    SortedMap<String, Object> sortedTargetRule = Maps.newTreeMap();
    sortedTargetRule.putAll(targetRule);
    return sortedTargetRule;
  }

}
