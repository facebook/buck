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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.InputRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

public class AuditInputCommand extends AbstractCommandRunner<AuditCommandOptions> {

  public AuditInputCommand(CommandRunnerParams params) {
    super(params);
  }

  @VisibleForTesting
  AuditInputCommand(PrintStream stdOut,
                    PrintStream stdErr,
                    Console console,
                    ProjectFilesystem projectFilesystem,
                    KnownBuildRuleTypes buildRuleTypes,
                    ArtifactCache artifactCache) {
    super(stdOut, stdErr, console, projectFilesystem, buildRuleTypes, artifactCache);
  }

  @Override
  AuditCommandOptions createOptions(BuckConfig buckConfig) {
    return new AuditCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(AuditCommandOptions options) throws IOException {
    // Create a PartialGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargets.
    final ImmutableSet<String> fullyQualifiedBuildTargets = ImmutableSet.copyOf(
        options.getArgumentsFormattedAsBuildTargets());

    if (fullyQualifiedBuildTargets.isEmpty()) {
      console.printFailure("Please specify at least one build target.");
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
          getProjectFilesystem().getProjectRoot(),
          getArtifactCache(),
          options.getDefaultIncludes());
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    return printInputs(partialGraph);
  }

  private int printInputs(final PartialGraph partialGraph) {
    // Traverse the PartialGraph and print out all of the inputs used to produce each BuildRule.
    // Keep track of the inputs that have been displayed to ensure that they are not displayed more
    // than once.
    new AbstractBottomUpTraversal<BuildRule, Void>(partialGraph.getDependencyGraph()) {

      final Set<InputRule> inputs = Sets.newHashSet();

      @Override
      public void visit(BuildRule rule) {
        for (InputRule input : rule.getInputs()) {
          boolean isNewInput = inputs.add(input);
          if (isNewInput) {
            stdOut.println(input.getFullyQualifiedName());
          }
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
