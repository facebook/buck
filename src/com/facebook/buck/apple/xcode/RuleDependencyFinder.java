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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.apple.IosTest;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.Iterator;

/**
 * Utility class for discovering related rules when generating amalgamated xcode projects.
 */
final class RuleDependencyFinder {

  /**
   * Utility class should not be instantiated.
   */
  private RuleDependencyFinder() {}

  /**
   * Retrieve all rules related to the given roots in the given graph.
   *
   * "Related" is defined as:
   * - The rules themselves
   * - Their transitive dependencies
   * - The tests of the above rules
   * - Any additional dependencies of the tests
   */
  public static ImmutableSet<BuildRule> getAllRules(
      PartialGraph graph,
      Iterable<BuildTarget> initialTargets) {

    ImmutableList.Builder<BuildRule> initialRulesBuilder = ImmutableList.builder();
    for (BuildTarget target : initialTargets) {
      initialRulesBuilder.add(graph.getActionGraph().findBuildRuleByTarget(target));
    }

    ImmutableSet<BuildRule> buildRules = gatherTransitiveDependencies(initialRulesBuilder.build());
    ImmutableMultimap<BuildRule, BuildRule> ruleToTestRules = buildRuleToTestRulesMap(graph);

    // Extract the test rules for the initial rules and their dependencies.
    ImmutableSet.Builder<BuildRule> testRulesBuilder = ImmutableSet.builder();
    for (BuildRule rule : buildRules) {
      testRulesBuilder.addAll(ruleToTestRules.get(rule));
    }
    ImmutableSet<BuildRule> additionalBuildRules = gatherTransitiveDependencies(
        testRulesBuilder.build());

    return ImmutableSet.<BuildRule>builder()
        .addAll(buildRules)
        .addAll(additionalBuildRules)
        .build();
  }

  private static ImmutableSet<BuildRule> gatherTransitiveDependencies(
      Iterable<? extends BuildRule> initial) {
    final ImmutableSet.Builder<BuildRule> buildRulesBuilder = ImmutableSet.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule> allDependenciesTraversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule>() {
          @Override
          protected Iterator<BuildRule> findChildren(BuildRule node) throws IOException {
            return node.getDeps().iterator();
          }
          @Override
          protected void onNodeExplored(BuildRule node) {
          }
          @Override
          protected void onTraversalComplete(Iterable<BuildRule> nodesInExplorationOrder) {
            buildRulesBuilder.addAll(nodesInExplorationOrder);
          }
        };
    try {
      allDependenciesTraversal.traverse(initial);
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e,
          "Cycle detected while gathering build rule dependencies for project generation:\n " +
              e.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return buildRulesBuilder.build();
  }

  /**
   * Create a map from targets to the tests that test them by examining
   * {@link com.facebook.buck.apple.IosTest#getSourceUnderTest()}.
   */
  private static ImmutableMultimap<BuildRule, BuildRule> buildRuleToTestRulesMap(
      PartialGraph graph) {
    ImmutableMultimap.Builder<BuildRule, BuildRule> ruleToTestRulesBuilder =
        ImmutableMultimap.builder();
    for (BuildTarget target : graph.getTargets()) {
      BuildRule rule = graph.getActionGraph().findBuildRuleByTarget(target);
      if (rule.getType().equals(IosTestDescription.TYPE)) {
        IosTest iosTest = (IosTest) Preconditions.checkNotNull(rule);
        for (BuildRule sourceRule : iosTest.getSourceUnderTest()) {
          ruleToTestRulesBuilder.put(sourceRule, rule);
        }
      }
    }
    return ruleToTestRulesBuilder.build();
  }
}
