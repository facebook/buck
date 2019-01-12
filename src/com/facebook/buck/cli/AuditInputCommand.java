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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.AbstractBottomUpTraversal;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditInputCommand extends AbstractCommand {

  private static final Logger LOG = Logger.get(AuditInputCommand.class);

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // TargetNodes for the specified BuildTargetPaths.
    ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, getArguments());

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    LOG.debug("Getting input for targets: %s", targets);

    TargetGraph graph;
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      graph =
          params
              .getParser()
              .buildTargetGraph(
                  params.getCell(),
                  getEnableParserProfiling(),
                  pool.getListeningExecutorService(),
                  targets);
    } catch (BuildFileParseException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }

    if (shouldGenerateJsonOutput()) {
      return printJsonInputs(params, graph);
    }
    return printInputs(params, graph);
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  ExitCode printJsonInputs(CommandRunnerParams params, TargetGraph graph) throws IOException {
    SortedMap<String, ImmutableSortedSet<Path>> targetToInputs = new TreeMap<>();

    new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(graph) {

      @Override
      public void visit(TargetNode<?> node) {
        Optional<Cell> cellRoot = params.getCell().getCellIfKnown(node.getBuildTarget());
        Cell cell = cellRoot.orElse(params.getCell());
        LOG.debug("Looking at inputs for %s", node.getBuildTarget().getFullyQualifiedName());

        ImmutableSortedSet.Builder<Path> targetInputs =
            new ImmutableSortedSet.Builder<>(Ordering.natural());
        for (Path input : node.getInputs()) {
          LOG.debug("Walking input %s", input);
          try {
            if (!cell.getFilesystem().exists(input)) {
              throw new HumanReadableException(
                  "Target %s refers to non-existent input file: %s",
                  node, params.getCell().getRoot().relativize(cell.getRoot().resolve(input)));
            }
            targetInputs.addAll(cell.getFilesystem().getFilesUnderPath(input));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        targetToInputs.put(node.getBuildTarget().getFullyQualifiedName(), targetInputs.build());
      }
    }.traverse();

    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), targetToInputs);

    return ExitCode.SUCCESS;
  }

  private ExitCode printInputs(CommandRunnerParams params, TargetGraph graph) {
    // Traverse the TargetGraph and print out all of the inputs used to produce each TargetNode.
    // Keep track of the inputs that have been displayed to ensure that they are not displayed more
    // than once.
    new AbstractBottomUpTraversal<TargetNode<?>, RuntimeException>(graph) {

      final Set<Path> inputs = new HashSet<>();

      @Override
      public void visit(TargetNode<?> node) {
        Optional<Cell> cellRoot = params.getCell().getCellIfKnown(node.getBuildTarget());
        Cell cell = cellRoot.orElse(params.getCell());
        for (Path input : node.getInputs()) {
          LOG.debug("Walking input %s", input);
          try {
            if (!cell.getFilesystem().exists(input)) {
              throw new HumanReadableException(
                  "Target %s refers to non-existent input file: %s",
                  node, params.getCell().getRoot().relativize(cell.getRoot().resolve(input)));
            }
            ImmutableSortedSet<Path> nodeContents =
                ImmutableSortedSet.copyOf(cell.getFilesystem().getFilesUnderPath(input));
            for (Path path : nodeContents) {
              putInput(params.getCell().getRoot().relativize(cell.getRoot().resolve(path)));
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }

      private void putInput(Path input) {
        boolean isNewInput = inputs.add(input);
        if (isNewInput) {
          params.getConsole().getStdOut().println(input);
        }
      }
    }.traverse();

    return ExitCode.SUCCESS;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' input files";
  }
}
