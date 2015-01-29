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

import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.log.Logger;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

public class AuditInputCommand extends AbstractCommandRunner<AuditCommandOptions> {

  private static final Logger LOG = Logger.get(AuditInputCommand.class);

  public AuditInputCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  AuditCommandOptions createOptions(BuckConfig buckConfig) {
    return new AuditCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(AuditCommandOptions options)
      throws IOException, InterruptedException {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // TargetNodes for the specified BuildTargets.
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      console.printBuildFailure("Please specify at least one build target.");
      return 1;
    }

    ImmutableSet<BuildTarget> targets = FluentIterable
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

    LOG.debug("Getting input for targets: %s", targets);

    TargetGraph graph;
    try {
      graph = getParser().buildTargetGraphForBuildTargets(
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

    if (options.shouldGenerateJsonOutput()) {
      return printJsonInputs(graph);
    }
    return printInputs(graph);
  }

  @VisibleForTesting
  int printJsonInputs(TargetGraph graph) throws IOException {
    final SortedMap<String, ImmutableSortedSet<Path>> targetToInputs =
        new TreeMap<>();

    new AbstractBottomUpTraversal<TargetNode<?>, Void>(graph) {

      @Override
      public void visit(TargetNode<?> node) {
        LOG.debug(
            "Looking at inputs for %s",
            node.getBuildTarget().getFullyQualifiedName());

        SortedSet<Path> targetInputs = new TreeSet<>();
        for (Path input : node.getInputs()) {
          LOG.debug("Walking input %s", input);
          try {
            if (!getProjectFilesystem().exists(input)) {
              throw new HumanReadableException(
                  "Target %s refers to non-existent input file: %s", node, input);
            }
            targetInputs.addAll(getProjectFilesystem().getFilesUnderPath(input));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        targetToInputs.put(
            node.getBuildTarget().getFullyQualifiedName(),
            ImmutableSortedSet.copyOf(targetInputs));
      }

      @Override
      public Void getResult() {
       return null;
      }

    }.traverse();

    getObjectMapper().writeValue(
        console.getStdOut(),
        targetToInputs);

    return 0;
  }

  private int printInputs(TargetGraph graph) {
    // Traverse the TargetGraph and print out all of the inputs used to produce each TargetNode.
    // Keep track of the inputs that have been displayed to ensure that they are not displayed more
    // than once.
    new AbstractBottomUpTraversal<TargetNode<?>, Void>(graph) {

      final Set<Path> inputs = Sets.newHashSet();

      @Override
      public void visit(TargetNode<?> node) {
        for (Path input : node.getInputs()) {
          LOG.debug("Walking input %s", input);
          try {
            if (!getProjectFilesystem().exists(input)) {
              throw new HumanReadableException(
                  "Target %s refers to non-existent input file: %s",
                  node,
                  input);
            }
            ImmutableSortedSet<Path> nodeContents = ImmutableSortedSet.copyOf(
                getProjectFilesystem().getFilesUnderPath(input));
            for (Path path : nodeContents) {
              putInput(path);
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }

      private void putInput(Path input) {
        boolean isNewInput = inputs.add(input);
        if (isNewInput) {
          getStdOut().println(input);
        }
      }

      @Override
      public Void getResult() {
        return null;
      }

    }.traverse();

    return 0;
  }

  @Override
  String getUsageIntro() {
    return "provides facilities to audit build targets' input files";
  }

}
