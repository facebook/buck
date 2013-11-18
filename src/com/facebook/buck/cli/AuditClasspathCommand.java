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

import com.facebook.buck.graph.Dot;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

public class AuditClasspathCommand extends AbstractCommandRunner<AuditCommandOptions> {

  public AuditClasspathCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  AuditCommandOptions createOptions(BuckConfig buckConfig) {
    return new AuditCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditCommandOptions options) throws IOException {
    // Create a PartialGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargets.
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      console.printBuildFailure("Please specify at least one build target.");
      return 1;
    }

    RawRulePredicate predicate = new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return fullyQualifiedBuildTargets.contains(buildTarget.getFullyQualifiedName());
      }
    };
    PartialGraph partialGraph;
    try {
      partialGraph = PartialGraph.createPartialGraph(predicate,
          getProjectFilesystem(),
          options.getDefaultIncludes(),
          getParser(),
          getBuckEventBus());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (options.shouldGenerateDotOutput()) {
      return printDotOutput(partialGraph.getDependencyGraph());
    } else if (options.shouldGenerateJsonOutput()) {
      return printJsonClasspath(partialGraph);
    } else {
      return printClasspath(partialGraph);
    }
  }

  @VisibleForTesting
  int printDotOutput(DependencyGraph dependencyGraph) {
    Dot<BuildRule> dot = new Dot<BuildRule>(
        dependencyGraph,
        "dependency_graph",
        new Function<BuildRule, String>() {
          @Override
          public String apply(BuildRule buildRule) {
            return "\"" + buildRule.getFullyQualifiedName() + "\"";
          }
        },
        getStdOut());
    try {
      dot.writeOutput();
    } catch (IOException e) {
      return 1;
    }
    return 0;
  }

  @VisibleForTesting
  int printClasspath(PartialGraph partialGraph) {
    List<BuildTarget> targets = partialGraph.getTargets();
    DependencyGraph graph = partialGraph.getDependencyGraph();
    SortedSet<String> classpathEntries = Sets.newTreeSet();

    for (BuildTarget target : targets) {
      BuildRule rule = graph.findBuildRuleByTarget(target);
      if (rule instanceof HasClasspathEntries) {
        HasClasspathEntries hasClasspathEntries = (HasClasspathEntries)rule;
        classpathEntries.addAll(hasClasspathEntries.getTransitiveClasspathEntries().values());
      } else {
        throw new HumanReadableException(rule.getFullyQualifiedName() + " is not a java-based" +
            " build target");
      }
    }

    for (String path : classpathEntries) {
      getStdOut().println(path);
    }

    return 0;
  }

  @VisibleForTesting
  int printJsonClasspath(PartialGraph partialGraph) throws IOException {
    DependencyGraph graph = partialGraph.getDependencyGraph();
    List<BuildTarget> targets = partialGraph.getTargets();
    Multimap<String, String> targetClasspaths = TreeMultimap.create();

    for (BuildTarget target : targets) {
      BuildRule rule = graph.findBuildRuleByTarget(target);
      if (!(rule instanceof HasClasspathEntries)) {
        continue;
      }

      HasClasspathEntries classpathEntries = (HasClasspathEntries) rule;
      targetClasspaths.putAll(
          target.getFullyQualifiedName(),
          classpathEntries.getTransitiveClasspathEntries().values());
    }

    ObjectMapper mapper = new ObjectMapper();

    // Note: using `asMap` here ensures that the keys are sorted
    mapper.writeValue(
        console.getStdOut(),
        targetClasspaths.asMap());

    return 0;
  }


  @Override
  String getUsageIntro() {
    return "provides facilities to audit build targets' classpaths";
  }

}
