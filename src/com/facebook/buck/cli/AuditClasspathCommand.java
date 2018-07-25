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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class AuditClasspathCommand extends AbstractCommand {

  /**
   * Expected usage:
   *
   * <pre>
   * buck audit classpath --dot //java/com/facebook/pkg:pkg > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot", usage = "Print dependencies as Dot graph")
  private boolean generateDotOutput;

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  @Option(name = "--json", usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  public ImmutableList<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargetPaths.
    ImmutableSet<BuildTarget> targets =
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig())
            .stream()
            .map(
                input ->
                    BuildTargetParser.INSTANCE.parse(
                        input,
                        BuildTargetPatternParser.fullyQualified(),
                        params.getCell().getCellPathResolver()))
            .collect(ImmutableSet.toImmutableSet());

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    TargetGraph targetGraph;
    try (CommandThreadManager pool =
        new CommandThreadManager("Audit", getConcurrencyLimit(params.getBuckConfig()))) {
      targetGraph =
          params
              .getParser()
              .buildTargetGraph(
                  params.getBuckEventBus(),
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

    try {
      if (shouldGenerateDotOutput()) {
        return printDotOutput(params, targetGraph);
      } else if (shouldGenerateJsonOutput()) {
        return printJsonClasspath(params, targetGraph, targets);
      } else {
        return printClasspath(params, targetGraph, targets);
      }
    } catch (VersionException e) {
      throw new HumanReadableException(e, MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  ExitCode printDotOutput(CommandRunnerParams params, TargetGraph targetGraph) {
    try {
      Dot.builder(targetGraph, "target_graph")
          .setNodeToName(
              targetNode -> "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"")
          .setNodeToTypeName(targetNode -> targetNode.getBuildRuleType().getName())
          .build()
          .writeOutput(params.getConsole().getStdOut());
    } catch (IOException e) {
      return ExitCode.FATAL_IO;
    }
    return ExitCode.SUCCESS;
  }

  @VisibleForTesting
  ExitCode printClasspath(
      CommandRunnerParams params, TargetGraph targetGraph, ImmutableSet<BuildTarget> targets)
      throws InterruptedException, VersionException {

    if (params.getBuckConfig().getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }

    ActionGraphBuilder graphBuilder =
        Preconditions.checkNotNull(
                new ActionGraphCache(params.getBuckConfig().getMaxActionGraphCacheEntries())
                    .getFreshActionGraph(
                        params.getBuckEventBus(),
                        targetGraph,
                        params.getCell().getCellProvider(),
                        params.getBuckConfig().getActionGraphParallelizationMode(),
                        params.getBuckConfig().getShouldInstrumentActionGraph(),
                        params.getPoolSupplier()))
            .getActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    SortedSet<Path> classpathEntries = new TreeSet<>();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(graphBuilder.requireRule(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries != null) {
        classpathEntries.addAll(
            pathResolver.getAllAbsolutePaths(hasClasspathEntries.getTransitiveClasspaths()));
      } else {
        throw new HumanReadableException(
            rule.getFullyQualifiedName() + " is not a java-based" + " build target");
      }
    }

    for (Path path : classpathEntries) {
      params.getConsole().getStdOut().println(path);
    }

    return ExitCode.SUCCESS;
  }

  @VisibleForTesting
  ExitCode printJsonClasspath(
      CommandRunnerParams params, TargetGraph targetGraph, ImmutableSet<BuildTarget> targets)
      throws IOException, InterruptedException, VersionException {

    if (params.getBuckConfig().getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }

    ActionGraphBuilder graphBuilder =
        Preconditions.checkNotNull(
                new ActionGraphCache(params.getBuckConfig().getMaxActionGraphCacheEntries())
                    .getFreshActionGraph(
                        params.getBuckEventBus(),
                        targetGraph,
                        params.getCell().getCellProvider(),
                        params.getBuckConfig().getActionGraphParallelizationMode(),
                        params.getBuckConfig().getShouldInstrumentActionGraph(),
                        params.getPoolSupplier()))
            .getActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Multimap<String, String> targetClasspaths = LinkedHashMultimap.create();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(graphBuilder.requireRule(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries == null) {
        continue;
      }
      targetClasspaths.putAll(
          target.getFullyQualifiedName(),
          hasClasspathEntries
              .getTransitiveClasspaths()
              .stream()
              .map(pathResolver::getAbsolutePath)
              .map(Object::toString)
              .collect(ImmutableList.toImmutableList()));
    }

    // Note: using `asMap` here ensures that the keys are sorted
    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), targetClasspaths.asMap());

    return ExitCode.SUCCESS;
  }

  @Nullable
  private HasClasspathEntries getHasClasspathEntriesFrom(BuildRule rule) {
    if (rule instanceof HasClasspathEntries) {
      return (HasClasspathEntries) rule;
    }
    return null;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }
}
