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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.versions.VersionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
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
  public int runWithoutHelp(final CommandRunnerParams params)
      throws IOException, InterruptedException {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargets.
    final ImmutableSet<BuildTarget> targets =
        getArgumentsFormattedAsBuildTargets(params.getBuckConfig())
            .stream()
            .map(
                input ->
                    BuildTargetParser.INSTANCE.parse(
                        input,
                        BuildTargetPatternParser.fullyQualified(),
                        params.getCell().getCellPathResolver()))
            .collect(MoreCollectors.toImmutableSet());

    if (targets.isEmpty()) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe("Please specify at least one build target."));
      return 1;
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
                  pool.getExecutor(),
                  targets);
    } catch (BuildFileParseException | BuildTargetException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }

    try {
      if (shouldGenerateDotOutput()) {
        return printDotOutput(params, targetGraph);
      } else if (shouldGenerateJsonOutput()) {
        return printJsonClasspath(params, targetGraph, targets);
      } else {
        return printClasspath(params, targetGraph, targets);
      }
    } catch (NoSuchBuildTargetException | VersionException e) {
      throw new HumanReadableException(e, MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  int printDotOutput(CommandRunnerParams params, TargetGraph targetGraph) {
    Dot<TargetNode<?, ?>> dot =
        new Dot<>(
            targetGraph,
            "target_graph",
            targetNode -> "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"",
            targetNode -> Description.getBuildRuleType(targetNode.getDescription()).getName(),
            params.getConsole().getStdOut());
    try {
      dot.writeOutput();
    } catch (IOException e) {
      return 1;
    }
    return 0;
  }

  @VisibleForTesting
  int printClasspath(
      CommandRunnerParams params, TargetGraph targetGraph, ImmutableSet<BuildTarget> targets)
      throws NoSuchBuildTargetException, InterruptedException, VersionException {

    if (params.getBuckConfig().getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }

    BuildRuleResolver resolver =
        Preconditions.checkNotNull(
                ActionGraphCache.getFreshActionGraph(params.getBuckEventBus(), targetGraph))
            .getResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    SortedSet<Path> classpathEntries = Sets.newTreeSet();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(resolver.requireRule(target));
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

    return 0;
  }

  @VisibleForTesting
  int printJsonClasspath(
      CommandRunnerParams params, TargetGraph targetGraph, ImmutableSet<BuildTarget> targets)
      throws IOException, NoSuchBuildTargetException, InterruptedException, VersionException {

    if (params.getBuckConfig().getBuildVersions()) {
      targetGraph =
          toVersionedTargetGraph(params, TargetGraphAndBuildTargets.of(targetGraph, targets))
              .getTargetGraph();
    }

    BuildRuleResolver resolver =
        Preconditions.checkNotNull(
                ActionGraphCache.getFreshActionGraph(params.getBuckEventBus(), targetGraph))
            .getResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Multimap<String, String> targetClasspaths = LinkedHashMultimap.create();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(resolver.requireRule(target));
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
              .collect(MoreCollectors.toImmutableList()));
    }

    // Note: using `asMap` here ensures that the keys are sorted
    ObjectMappers.WRITER.writeValue(params.getConsole().getStdOut(), targetClasspaths.asMap());

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
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }
}
