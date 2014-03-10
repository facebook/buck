/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.test.result.groups;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.test.TestResults;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * Group TestResults together into cliques based on the graph built by following 'deps' and
 * 'source_under_test' edges.
 */
public class TestResultsGrouper {

  // Maps a library to a set of tests that test it.
  private final SetMultimap<BuildRule, TestRule> testRulesForBuildRules = HashMultimap.create();

  // Maps a test to a set of libraries it *indirectly* depends on.
  private final SetMultimap<TestRule, BuildRule> allAncestorDependenciesForTestRule =
      HashMultimap.create();

  // Maps a test onto its results, as given to us by post().
  private final Map<TestRule, TestResults> allTestResults = Maps.newHashMap();

  // The set of tests we've already grouped-and-flushed.
  private final Set<TestRule> flushedTestRules = Sets.newHashSet();

  // The set of tests we've seen, but not yet been able to group-and-flush.
  private final Set<TestRule> pendingTestRules = Sets.newHashSet();

  public TestResultsGrouper(Iterable<TestRule> testRules) {
    for (TestRule testRule : testRules) {
      allTestResults.put(testRule, null);

      ImmutableSet<BuildRule> sourcesUnderTest = testRule.getSourceUnderTest();
      for (BuildRule buildRule : sourcesUnderTest) {
        // We have found the (test => library) edge, so store the inverse (library => test) edge.
        testRulesForBuildRules.put(buildRule, testRule);

        // Store all the ancestor dependencies (but not the direct dependency).
        for (BuildRule dep : getAllDependencies(buildRule)) {
          if (dep != buildRule) {
            allAncestorDependenciesForTestRule.put(testRule, dep);
          }
        }
      }
    }
  }

  /**
   * Accept and enqueue a set of TestResults for the given TestRule.  When enough results have been
   * received that complete cliques of test-rule / test-results can be formed, they are returned.
   *
   * @param finishedTest The test that just finished.
   * @param testResults The test's results.
   * @return The TestResults (keyed by respective TestRules) which, as a result of this most recent
   *     test result, now form complete cliques.
   */
  public synchronized Map<TestRule, TestResults> post(
      TestRule finishedTest,
      TestResults testResults) {
    String targetName = finishedTest.getBuildTarget().getFullyQualifiedName();
    Preconditions.checkState(allTestResults.containsKey(finishedTest),
        "Unexpected test results found for test '%s'", targetName);
    Preconditions.checkState(allTestResults.get(finishedTest) == null,
        "Duplicate test results found for test '%s'", targetName);

    allTestResults.put(finishedTest, testResults);
    pendingTestRules.add(finishedTest);

    return ImmutableMap.copyOf(flush());
  }

  ImmutableSet<BuildRule> getAllDependencies(BuildRule rootBuildRule) {
    Set<BuildRule> allDeps = Sets.newHashSet();
    Set<BuildRule> seen = Sets.newHashSet();
    LinkedList<BuildRule> queue = new LinkedList<>();
    queue.add(rootBuildRule);
    while (!queue.isEmpty()) {
      BuildRule node = queue.remove();
      allDeps.add(node);
      for (BuildRule child : node.getDeps()) {
        if (seen.add(child)) {
          queue.add(child);
        }
      }
    }
    return ImmutableSet.copyOf(allDeps);
  }

  private Set<TestRule> getDependentTestRules(TestRule testRule) {
    ImmutableSet.Builder<TestRule> dependentTestRulesBuilder = new ImmutableSet.Builder<>();
    Set<BuildRule> deps = allAncestorDependenciesForTestRule.get(testRule);
    for (BuildRule dep : deps) {
      // Dependencies may not necessarily have a TestRule that tests them.
      //
      //   BuildRules: :parent ----> :child ----> :grandchild
      //   TestRules:  ^ParentTest  ^ChildTest
      //
      // It might be that no one wrote a test (:grandchild) or this test run wasn't asked to include
      // the relevant test ("buck test :parent").
      //
      if (testRulesForBuildRules.containsKey(dep)) {
        Set<TestRule> dependentTestRules = testRulesForBuildRules.get(dep);
        dependentTestRulesBuilder.addAll(dependentTestRules);
      }
    }
    return dependentTestRulesBuilder.build();
  }

  private Map<TestRule, TestResults> flush() {
    Map<TestRule, TestResults> result = Maps.newHashMap();

    for (TestRule pendingTestRule : pendingTestRules) {
      Map<TestRule, TestResults> toAdd = flushOne(pendingTestRule);
      for (TestRule flushableTestRule : toAdd.keySet()) {
        if (!flushedTestRules.contains(flushableTestRule)) {
          TestResults flushableTestResults = toAdd.get(flushableTestRule);
          result.put(flushableTestRule, flushableTestResults);
          flushedTestRules.add(flushableTestRule);
        }
      }
    }

    pendingTestRules.removeAll(flushedTestRules);

    return result;
  }

  /**
   * The returned Map may contain test results that we've already flushed.
   *
   * For example, if we have Parent depending on Child, with a ParentTest and ChildTest, the
   * ChildTest may flush (child-results) first (it has no dependencies), and the ParentTest will
   * build the set (parent-results, child-results), but we should not re-flush child-results.
   */
  private Map<TestRule, TestResults> flushOne(TestRule ourTestRule) {
    Map<TestRule, TestResults> result = Maps.newHashMap();

    TestResults ourTestResults = allTestResults.get(ourTestRule);
    result.put(ourTestRule, ourTestResults);

    int failedDepsCount = 0;
    Set<TestRule> dependentTestRules = getDependentTestRules(ourTestRule);
    for (TestRule dependentTestRule : dependentTestRules) {
      TestResults dependentTestResults = allTestResults.get(dependentTestRule);
      if (dependentTestResults == null) {
        return ImmutableMap.of();
      } else {
        if (!dependentTestResults.isSuccess()) {
          failedDepsCount++;
        }
        result.put(dependentTestRule, dependentTestResults);
      }
    }

    ourTestResults.setDependenciesPassTheirTests(failedDepsCount == 0);

    return result;
  }
}
