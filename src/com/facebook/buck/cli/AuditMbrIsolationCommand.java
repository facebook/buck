/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphConfig;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.modern.tools.IsolationChecker;
import com.facebook.buck.rules.modern.tools.IsolationChecker.FailureReporter;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.kohsuke.args4j.Argument;

/**
 * Generates an isolation report. This report includes information indicating which nodes in the
 * action graph are isolateable (e.g. able to run in remote execution) and the requirements to do
 * that (absolute paths needed, toolchains needed, etc).
 */
public class AuditMbrIsolationCommand extends AbstractCommand {
  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    try {
      // Create a TargetGraph that is composed of the transitive closure of all of the dependent
      // BuildRules for the specified BuildTargetPaths.
      ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, getArguments());

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
                          params
                              .getBuckConfig()
                              .getView(ActionGraphConfig.class)
                              .getActionGraphParallelizationMode(),
                          params
                              .getBuckConfig()
                              .getView(ActionGraphConfig.class)
                              .getShouldInstrumentActionGraph(),
                          params.getPoolSupplier()))
              .getActionGraphBuilder();
      graphBuilder.requireAllRules(targets);

      SerializationReportGenerator reportGenerator = new SerializationReportGenerator();

      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      IsolationChecker isolationChecker =
          new IsolationChecker(
              ruleFinder,
              params.getCell().getCellPathResolver(),
              reportGenerator.getFailureReporter());
      AbstractBreadthFirstTraversal.<BuildRule>traverse(
          targets.stream().map(graphBuilder::getRule).collect(Collectors.toList()),
          rule -> {
            isolationChecker.check(rule);
            ImmutableList.Builder<BuildRule> depsBuilder = ImmutableList.builder();
            depsBuilder.addAll(rule.getBuildDeps());
            if (rule instanceof HasRuntimeDeps) {
              depsBuilder.addAll(
                  graphBuilder.getAllRules(
                      ((HasRuntimeDeps) rule)
                          .getRuntimeDeps(ruleFinder)
                          .collect(Collectors.toList())));
            }
            return depsBuilder.build();
          });

      String report = Joiner.on("\n").join(reportGenerator.generate());
      params.getConsole().getStdOut().println(report);
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When inspecting serialization state of the action graph.");
    }

    return ExitCode.SUCCESS;
  }

  private static List<Entry<String, Collection<String>>> asSortedEntries(
      Multimap<String, String> failure) {
    return failure
        .asMap()
        .entrySet()
        .stream()
        .sorted(Comparator.comparing(e -> -e.getValue().size()))
        .collect(Collectors.toList());
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }

  private static class SerializationReportGenerator {
    // Maps a rule type to a set of failures for that rule type. The set of failures in turn is a
    // map of failure message to a set of targets that failed in that way.
    Map<String, Multimap<String, String>> failuresByRuleType = new HashMap<>();
    Map<String, Multimap<String, String>> absolutePathsRequired = new HashMap<>();

    Multimap<String, String> successByType = ArrayListMultimap.create();
    Multimap<String, String> notMigratedByType = ArrayListMultimap.create();
    private FailureReporter failureReporter =
        new FailureReporter() {
          @Override
          public void reportNotMbr(BuildRule instance) {
            if (instance.hasBuildSteps()) {
              notMigratedByType.put(getRuleTypeString(instance), instance.getFullyQualifiedName());
            }
          }

          @Override
          public void reportSerializationFailure(
              BuildRule instance, String crumbs, String message) {
            String error = String.format("%s %s", crumbs, message);
            Multimap<String, String> failedTargetsByMessage =
                failuresByRuleType.computeIfAbsent(
                    getRuleTypeString(instance), ignored -> ArrayListMultimap.create());
            failedTargetsByMessage.put(error, instance.getFullyQualifiedName());
          }

          @Override
          public void reportAbsolutePath(BuildRule instance, String crumbs, Path path) {
            Multimap<String, String> inner =
                absolutePathsRequired.computeIfAbsent(
                    path.toString(), ignored -> ArrayListMultimap.create());
            inner.put(crumbs, instance.getFullyQualifiedName());
          }

          @Override
          public void reportSuccess(BuildRule instance) {
            successByType.put(getRuleTypeString(instance), instance.getFullyQualifiedName());
          }

          public String getRuleTypeString(BuildRule instance) {
            return instance.getType();
          }
        };

    public FailureReporter getFailureReporter() {
      return failureReporter;
    }

    public List<String> generate() {
      ReportBuilder builder = new ReportBuilder();
      if (successByType.isEmpty()) {
        builder.addLine("No rules are serializable.");
      } else {
        for (Entry<String, Collection<String>> instance : asSortedEntries(successByType)) {
          builder.addLine(
              "%s instances of %s are serializable.",
              instance.getValue().size(), instance.getKey());
        }
      }

      builder.addSeparator();
      if (notMigratedByType.isEmpty()) {
        builder.addLine("All used rules are migrated to ModernBuildRule.");
      } else {
        for (Entry<String, Collection<String>> instance : asSortedEntries(notMigratedByType)) {
          builder.addLine(
              "%s instances of %s which is not yet migrated to ModernBuildRule.",
              instance.getValue().size(), instance.getKey());
        }
      }

      builder.addSeparator();
      if (failuresByRuleType.isEmpty()) {
        builder.addLine("There's no failures for rules migrated to ModernBuildRule.");
      } else {
        for (Map.Entry<String, Multimap<String, String>> failure :
            failuresByRuleType
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> -entry.getValue().size()))
                .collect(Collectors.toList())) {
          builder.addLine(
              "%s failures for rules of type %s.", failure.getValue().size(), failure.getKey());
          for (Entry<String, Collection<String>> instance : asSortedEntries(failure.getValue())) {
            builder.addLine("  %s: %s", instance.getValue().size(), instance.getKey());

            int count = 0;
            int max = 3;
            for (String target : instance.getValue()) {
              if (count >= max) {
                builder.addLine("    ...");
                break;
              }
              builder.addLine("    %s", target);
              count++;
            }
          }
        }
      }

      builder.addSeparator();

      if (absolutePathsRequired.isEmpty()) {
        builder.addLine("Didn't find any references to absolute paths.");
      } else {
        for (Map.Entry<String, Multimap<String, String>> requiredPath :
            absolutePathsRequired
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> -entry.getValue().size()))
                .collect(Collectors.toList())) {
          builder.addLine(
              "%s referenced by %s rules.", requiredPath.getKey(), requiredPath.getValue().size());
          for (Entry<String, Collection<String>> instance :
              asSortedEntries(requiredPath.getValue())) {
            builder.addLine("  %s: %s", instance.getValue().size(), instance.getKey());

            int count = 0;
            int max = 3;
            for (String target : instance.getValue()) {
              if (count >= max) {
                builder.addLine("    ...");
                break;
              }
              builder.addLine("    %s", target);
              count++;
            }
          }
        }
      }

      return builder.reportLines;
    }

    private static class ReportBuilder {
      List<String> reportLines = new ArrayList<>();

      private void addSeparator() {
        reportLines.add("-------------------------------------------------------------");
        reportLines.add("-------------------------------------------------------------");
        reportLines.add("-------------------------------------------------------------");
      }

      private void addLine(String message) {
        reportLines.add(message);
      }

      private void addLine(String format, Object... args) {
        reportLines.add(String.format(format, args));
      }
    }
  }
}
