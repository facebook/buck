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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class AuditInputCommand extends AbstractCommandRunner<AuditCommandOptions> {

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

    if (options.shouldGenerateJsonOutput()) {
      return printJsonInputs(partialGraph);
    }
    return printInputs(partialGraph);
  }

  @VisibleForTesting
  int printJsonInputs(PartialGraph partialGraph) throws IOException {
    final Multimap<String, String> targetInputs = TreeMultimap.create();

    new AbstractBottomUpTraversal<BuildRule, Void>(partialGraph.getActionGraph()) {

      @Override
      public void visit(BuildRule rule) {
        for (Path input : rule.getInputs()) {
          // TODO(user) remove `toString` once Jackson supports serializing Path instances
          targetInputs.put(rule.getFullyQualifiedName(), input.toString());
        }
      }

      @Override
      public Void getResult() {
       return null;
      }

    }.traverse();
    ObjectMapper mapper = new ObjectMapper();

    // Note: using `asMap` here ensures that the keys are sorted
    mapper.writeValue(
        console.getStdOut(),
        targetInputs.asMap());

    return 0;
  }

  private int printInputs(final PartialGraph partialGraph) {
    // Traverse the PartialGraph and print out all of the inputs used to produce each BuildRule.
    // Keep track of the inputs that have been displayed to ensure that they are not displayed more
    // than once.
    new AbstractBottomUpTraversal<BuildRule, Void>(partialGraph.getActionGraph()) {

      final Set<Path> inputs = Sets.newHashSet();

      @Override
      public void visit(BuildRule rule) {
        for (Path input : rule.getInputs()) {
          boolean isNewInput = inputs.add(input);
          if (isNewInput) {
            getStdOut().println(input);
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
