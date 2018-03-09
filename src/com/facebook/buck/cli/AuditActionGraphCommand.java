/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** Command that dumps basic information about the action graph. */
public class AuditActionGraphCommand extends AbstractCommand {
  private static final Logger LOG = Logger.get(AuditActionGraphCommand.class);

  @Option(name = "--dot", usage = "Print result in graphviz dot format.")
  private boolean generateDotOutput;

  @Argument private List<String> targetSpecs = new ArrayList<>();

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    try (CommandThreadManager pool =
            new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()));
        CloseableMemoizedSupplier<ForkJoinPool> poolSupplier =
            getForkJoinPoolSupplier(params.getBuckConfig())) {
      // Create the target graph.
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  pool.getListeningExecutorService(),
                  parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), targetSpecs),
                  params.getBuckConfig().getView(ParserConfig.class).getDefaultFlavorsMode());
      TargetGraphAndBuildTargets targetGraphAndBuildTargets =
          params.getBuckConfig().getBuildVersions()
              ? toVersionedTargetGraph(params, unversionedTargetGraphAndBuildTargets)
              : unversionedTargetGraphAndBuildTargets;

      // Create the action graph.
      ActionGraphAndResolver actionGraphAndResolver =
          params
              .getActionGraphCache()
              .getActionGraph(
                  params.getBuckEventBus(),
                  targetGraphAndBuildTargets.getTargetGraph(),
                  params.getCell().getCellProvider(),
                  params.getBuckConfig(),
                  params.getRuleKeyConfiguration(),
                  poolSupplier);

      // Dump the action graph.
      if (generateDotOutput) {
        dumpAsDot(actionGraphAndResolver.getActionGraph(), params.getConsole().getStdOut());
      } else {
        dumpAsJson(actionGraphAndResolver.getActionGraph(), params.getConsole().getStdOut());
      }
    } catch (BuildFileParseException | VersionException e) {
      // The exception should be logged with stack trace instead of only emitting the error.
      LOG.info(e, "Caught an exception and treating it as a command error.");
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    }
    return ExitCode.SUCCESS;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Dump basic action graph node and connectivity information.";
  }

  /**
   * Dump basic information about the action graph to the given stream in a simple JSON format.
   *
   * <p>The passed in stream is not closed after this operation.
   */
  private static void dumpAsJson(ActionGraph graph, OutputStream out) throws IOException {
    try (JsonGenerator json =
        new JsonFactory()
            .createGenerator(out)
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)) {
      json.writeStartArray();
      for (BuildRule node : graph.getNodes()) {
        json.writeStartObject();
        json.writeStringField("name", node.getFullyQualifiedName());
        json.writeStringField("type", node.getType());
        {
          json.writeArrayFieldStart("buildDeps");
          for (BuildRule dep : node.getBuildDeps()) {
            json.writeString(dep.getFullyQualifiedName());
          }
          json.writeEndArray();
        }
        json.writeEndObject();
      }
      json.writeEndArray();
    }
  }

  private static void dumpAsDot(ActionGraph graph, DirtyPrintStreamDecorator out)
      throws IOException {
    MutableDirectedGraph<BuildRule> dag = new MutableDirectedGraph<>();
    graph.getNodes().forEach(dag::addNode);
    graph.getNodes().forEach(from -> from.getBuildDeps().forEach(to -> dag.addEdge(from, to)));
    Dot.builder(new DirectedAcyclicGraph<>(dag), "action_graph")
        .setNodeToName(BuildRule::getFullyQualifiedName)
        .setNodeToTypeName(BuildRule::getType)
        .build()
        .writeOutput(out);
  }
}
