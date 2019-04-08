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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.VersionException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/** Command that dumps basic information about the action graph. */
public class AuditActionGraphCommand extends AbstractCommand {

  /** Defines how node parameters are rendered */
  private enum NodeView {
    // Only node names are exported
    NameOnly,
    // Additional node attributes are exported too
    Extended
  }

  private static final Logger LOG = Logger.get(AuditActionGraphCommand.class);

  @Option(name = "--dot", usage = "Print result in graphviz dot format.")
  private boolean generateDotOutput;

  @Option(
      name = "--node-view",
      usage = "Whether to include additional build rule parameters as node attributes")
  private NodeView nodeView = NodeView.NameOnly;

  @Option(name = "--include-runtime-deps", usage = "Include runtime deps in addition to build deps")
  private boolean includeRuntimeDeps;

  @Argument private List<String> targetSpecs = new ArrayList<>();

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      // Create the target graph.
      TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets =
          params
              .getParser()
              .buildTargetGraphWithoutConfigurationTargets(
                  createParsingContext(params.getCell(), pool.getListeningExecutorService())
                      .withApplyDefaultFlavorsMode(
                          params
                              .getBuckConfig()
                              .getView(ParserConfig.class)
                              .getDefaultFlavorsMode()),
                  parseArgumentsAsTargetNodeSpecs(
                      params.getCell(), params.getBuckConfig(), targetSpecs),
                  params.getTargetConfiguration());
      TargetGraphAndBuildTargets targetGraphAndBuildTargets =
          params.getBuckConfig().getView(BuildBuckConfig.class).getBuildVersions()
              ? toVersionedTargetGraph(params, unversionedTargetGraphAndBuildTargets)
              : unversionedTargetGraphAndBuildTargets;

      // Create the action graph.
      ActionGraphAndBuilder actionGraphAndBuilder =
          params
              .getActionGraphProvider()
              .getActionGraph(targetGraphAndBuildTargets.getTargetGraph());
      SourcePathRuleFinder ruleFinder =
          new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder());
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

      // Dump the action graph.
      if (generateDotOutput) {
        dumpAsDot(
            actionGraphAndBuilder.getActionGraph(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            ruleFinder,
            includeRuntimeDeps,
            nodeView,
            params.getConsole().getStdOut());
      } else {
        dumpAsJson(
            actionGraphAndBuilder.getActionGraph(),
            actionGraphAndBuilder.getActionGraphBuilder(),
            ruleFinder,
            pathResolver,
            includeRuntimeDeps,
            nodeView,
            params.getConsole().getStdOut());
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
  private static void dumpAsJson(
      ActionGraph graph,
      ActionGraphBuilder actionGraphBuilder,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      boolean includeRuntimeDeps,
      NodeView nodeView,
      OutputStream out)
      throws IOException {
    try (JsonGenerator json =
        new JsonFactory()
            .createGenerator(out)
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)) {
      json.writeStartArray();
      for (BuildRule node : graph.getNodes()) {
        writeJsonObjectForBuildRule(
            json, node, actionGraphBuilder, ruleFinder, pathResolver, includeRuntimeDeps, nodeView);
      }
      json.writeEndArray();
    }
  }

  private static void writeJsonObjectForBuildRule(
      JsonGenerator json,
      BuildRule node,
      ActionGraphBuilder actionGraphBuilder,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      boolean includeRuntimeDeps,
      NodeView nodeView)
      throws IOException {
    json.writeStartObject();
    json.writeStringField("name", node.getFullyQualifiedName());
    json.writeStringField("type", node.getType());
    {
      json.writeArrayFieldStart("buildDeps");
      for (BuildRule dep : node.getBuildDeps()) {
        json.writeString(dep.getFullyQualifiedName());
      }
      json.writeEndArray();
      if (includeRuntimeDeps) {
        json.writeArrayFieldStart("runtimeDeps");
        for (BuildRule dep : getRuntimeDeps(node, actionGraphBuilder, ruleFinder)) {
          json.writeString(dep.getFullyQualifiedName());
        }
        json.writeEndArray();
      }
      SourcePath sourcePathToOutput = node.getSourcePathToOutput();
      if (sourcePathToOutput != null) {
        Path outputPath = pathResolver.getAbsolutePath(sourcePathToOutput);
        json.writeStringField("outputPath", outputPath.toString());
      }
    }
    if (nodeView == NodeView.Extended) {
      ImmutableSortedMap<String, String> attrs = getNodeAttributes(node);
      for (ImmutableSortedMap.Entry<String, String> attr : attrs.entrySet()) {
        // add 'buck_' prefix to avoid name collisions and make it compatible with DOT output
        json.writeStringField("buck_" + attr.getKey(), attr.getValue());
      }
    }
    json.writeEndObject();
  }

  private static void dumpAsDot(
      ActionGraph graph,
      ActionGraphBuilder actionGraphBuilder,
      SourcePathRuleFinder ruleFinder,
      boolean includeRuntimeDeps,
      NodeView nodeView,
      DirtyPrintStreamDecorator out)
      throws IOException {
    MutableDirectedGraph<BuildRule> dag = new MutableDirectedGraph<>();
    graph.getNodes().forEach(dag::addNode);
    graph.getNodes().forEach(from -> from.getBuildDeps().forEach(to -> dag.addEdge(from, to)));
    if (includeRuntimeDeps) {
      graph
          .getNodes()
          .forEach(
              from ->
                  getRuntimeDeps(from, actionGraphBuilder, ruleFinder)
                      .forEach(to -> dag.addEdge(from, to)));
    }
    Dot.Builder<BuildRule> builder =
        Dot.builder(new DirectedAcyclicGraph<>(dag), "action_graph")
            .setNodeToName(BuildRule::getFullyQualifiedName)
            .setNodeToTypeName(BuildRule::getType);
    if (nodeView == NodeView.Extended) {
      builder.setNodeToAttributes(AuditActionGraphCommand::getNodeAttributes);
    }
    builder.build().writeOutput(out);
  }

  private static ImmutableSortedMap<String, String> getNodeAttributes(BuildRule rule) {
    ImmutableSortedMap.Builder<String, String> attrs = ImmutableSortedMap.naturalOrder();
    attrs.put("short_name", rule.getBuildTarget().getShortName());
    attrs.put("type", rule.getType());
    attrs.put(
        "output",
        rule.getSourcePathToOutput() == null ? "" : rule.getSourcePathToOutput().toString());
    attrs.put("cacheable", rule.isCacheable() ? "true" : "false");
    attrs.put("flavored", rule.getBuildTarget().isFlavored() ? "true" : "false");
    return attrs.build();
  }

  private static SortedSet<BuildRule> getRuntimeDeps(
      BuildRule buildRule, ActionGraphBuilder actionGraphBuilder, SourcePathRuleFinder ruleFinder) {
    if (!(buildRule instanceof HasRuntimeDeps)) {
      return ImmutableSortedSet.of();
    }
    return actionGraphBuilder.getAllRules(
        RichStream.from(((HasRuntimeDeps) buildRule).getRuntimeDeps(ruleFinder)).toOnceIterable());
  }
}
