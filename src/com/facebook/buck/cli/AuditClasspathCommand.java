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
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class AuditClasspathCommand extends AbstractCommandRunner<AuditCommandOptions> {

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public AuditClasspathCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  AuditCommandOptions createOptions(BuckConfig buckConfig) {
    return new AuditCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditCommandOptions options)
      throws IOException, InterruptedException {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargets.
    final ImmutableSet<BuildTarget> targets = FluentIterable
        .from(options.getArgumentsFormattedAsBuildTargets())
        .transform(new Function<String, BuildTarget>() {
                     @Override
                     public BuildTarget apply(String input) {
                       return getParser().getBuildTargetParser().parse(
                           input,
                           BuildTargetPatternParser.fullyQualified(
                               getParser().getBuildTargetParser()));
                     }
                   })
        .toSet();

    if (targets.isEmpty()) {
      console.printBuildFailure("Please specify at least one build target.");
      return 1;
    }

    TargetGraph targetGraph;
    try {
      targetGraph = getParser().buildTargetGraphForBuildTargets(
          targets,
          new ParserConfig(options.getBuckConfig()),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (options.shouldGenerateDotOutput()) {
      return printDotOutput(targetGraph);
    } else if (options.shouldGenerateJsonOutput()) {
      return printJsonClasspath(targetGraph, targets);
    } else {
      return printClasspath(targetGraph, targets);
    }
  }

  @VisibleForTesting
  int printDotOutput(TargetGraph targetGraph) {
    Dot<TargetNode<?>> dot = new Dot<>(
        targetGraph,
        "target_graph",
        new Function<TargetNode<?>, String>() {
          @Override
          public String apply(TargetNode<?> targetNode) {
            return "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"";
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
  int printClasspath(TargetGraph targetGraph, ImmutableSet<BuildTarget> targets) {
    ActionGraph graph = targetGraphTransformer.apply(targetGraph);
    SortedSet<Path> classpathEntries = Sets.newTreeSet();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(graph.findBuildRuleByTarget(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries != null) {
        classpathEntries.addAll(hasClasspathEntries.getTransitiveClasspathEntries().values());
      } else {
        throw new HumanReadableException(rule.getFullyQualifiedName() + " is not a java-based" +
            " build target");
      }
    }

    for (Path path : classpathEntries) {
      getStdOut().println(path);
    }

    return 0;
  }

  @VisibleForTesting
  int printJsonClasspath(TargetGraph targetGraph, ImmutableSet<BuildTarget> targets)
      throws IOException {
    ActionGraph graph = targetGraphTransformer.apply(targetGraph);
    Multimap<String, String> targetClasspaths = LinkedHashMultimap.create();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(graph.findBuildRuleByTarget(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries == null) {
        continue;
      }
      targetClasspaths.putAll(
          target.getFullyQualifiedName(),
          Iterables.transform(
              hasClasspathEntries.getTransitiveClasspathEntries().values(),
              Functions.toStringFunction()));
    }

    // Note: using `asMap` here ensures that the keys are sorted
    getObjectMapper().writeValue(console.getStdOut(), targetClasspaths.asMap());

    return 0;
  }

  @Nullable
  private HasClasspathEntries getHasClasspathEntriesFrom(BuildRule rule) {
    if (rule instanceof HasClasspathEntries) {
      return (HasClasspathEntries) rule;
    }
    return null;
  }

  @Override
  String getUsageIntro() {
    return "provides facilities to audit build targets' classpaths";
  }

}
