/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.DefaultJavaLibraryRule.Builder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;

public class QueryCommandTest {

  private TestConsole console;
  private QueryCommand queryCommand;

  private PartialGraph testGraph;

  /**
   * Sets up the following dependency graph:
   *
   *        D
   *       / \
   *      B   C   E
   *       \ /
   *        A
   */
  @Before
  public void setUp() {
    console = new TestConsole();

    CommandRunnerParams queryParams = CommandRunnerParamsForTesting
        .builder()
        .setConsole(console)
        .build();
    queryCommand = new QueryCommand(queryParams);

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    addRule(ruleResolver, "//:E");
    addRule(ruleResolver, "//:D");
    addRule(ruleResolver, "//:C", ImmutableList.of("//:D"));
    addRule(ruleResolver, "//:B", ImmutableList.of("//:D"));
    addRule(ruleResolver, "//:A", ImmutableList.of("//:B", "//:C"));
    testGraph = createGraphFromBuildRules(ruleResolver, ImmutableList.of("//:A"));
  }

  /** Test that the pattern we use to match query strings matches the right stuff. */
  @Test
  public void testArrowPattern() {
    Matcher simpleMatch = DependencyQuery.ARROW_PATTERN.matcher("//:foo -> //:bar");
    assertTrue(simpleMatch.matches());
    assertEquals("//:foo", simpleMatch.group(1));
    assertEquals("", simpleMatch.group(2));
    assertEquals("//:bar", simpleMatch.group(3));

    Matcher matchTargetsWithHyphens = DependencyQuery.ARROW_PATTERN.matcher(
        "//src/com/buck:some-rules-> -> //src/com/buck/util:other-rules");
    assertTrue(matchTargetsWithHyphens.matches());
    assertEquals("//src/com/buck:some-rules->", matchTargetsWithHyphens.group(1));
    assertEquals("", matchTargetsWithHyphens.group(2));
    assertEquals("//src/com/buck/util:other-rules", matchTargetsWithHyphens.group(3));

    Matcher queryWithMissingArguments = DependencyQuery.ARROW_PATTERN.matcher(" -> ");
    assertFalse(queryWithMissingArguments.matches());
  }

  @Test
  public void testQueryParser() {
    DependencyQuery query;
    query = DependencyQuery.parseQueryString("//:ased>> -4> //:>- ");
    assertEquals(4, query.getDepth().get().longValue());
    assertEquals("//:ased>>", query.getTarget());
    assertEquals("//:>-", query.getSource().get());

    query = DependencyQuery.parseQueryString("//:dd -*>");
    assertFalse(query.getDepth().isPresent());
    assertEquals("//:dd", query.getTarget());
    assertFalse(query.getSource().isPresent());

    try {
      query = DependencyQuery.parseQueryString("//:hello - > //:goodbye");
      fail("Should not have parsed noncontinguous arrow");
    } catch (HumanReadableException e) {
      assertEquals("Invalid query string: //:hello - > //:goodbye.", e.getMessage());
    }

    try {
      query = DependencyQuery.parseQueryString("-> //:goodbye");
      fail("Query needs source");
    } catch (HumanReadableException e) {
      assertEquals("Invalid query string: -> //:goodbye.", e.getMessage());
    }
  }

  @Test
  public void testDependencyQuery() {
    testQuery("//:A ->", "//:A\n//:B\n//:C\n//:D");
    testQuery("//:B -1> ", "//:B\n//:D");
  }

  @Test
  public void testPathQuery() {
    testQuery("//:A -> //:D", "//:A -> //:B -> //:D");
    testQuery("//:A -1> //:D", "");
    testQuery("//:B -*> //:C ", "");
    testQueryError("//:Ba -*> //:A", "Unknown build target: //:Ba.");
  }

  private void testQuery(String queryString, String expectedOut) {
    DependencyQuery query = DependencyQuery.parseQueryString(queryString);
    String output = queryCommand.executeQuery(testGraph, query);
    assertEqualsModuloLines(expectedOut, output);
  }

  private void testQueryError(String queryString, String expectedErr) {
    try {
      DependencyQuery query = DependencyQuery.parseQueryString(queryString);
      queryCommand.executeQuery(testGraph, query);
      fail(String.format("Query: %s should have failed", queryString));
    } catch (HumanReadableException e) {
      assertEquals(expectedErr, e.getMessage());
    }
  }

  /**
   * Compare two strings for equality, not considering the orderings of their
   * lines. This is used because don't care what order neo4j traverses dependencies.
   * Right now it actually displays dependencies and paths in increasing distance
   * order, but within one fixed distance there is no fixed order.
   *
   * @param expected lines of string output expected.
   * @param actual lines of string output we actually have.
   */
  private void assertEqualsModuloLines(String expected, String actual) {
    assertEquals(ImmutableSet.copyOf(expected.split("\n")),
        ImmutableSet.copyOf(actual.split("\n")));
  }

  private void addRule(BuildRuleResolver ruleResolver, String target) {
    addRule(ruleResolver, target, ImmutableList.<String>of());
  }

  private void addRule(BuildRuleResolver ruleResolver,
      String target,
      ImmutableList<String> dependencies) {
    Builder builder = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(
        new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance(target));
    for(String dependency : dependencies) {
      builder = builder.addDep(BuildTargetFactory.newInstance(dependency));
    }
    ruleResolver.buildAndAddToIndex(builder);
  }

  private PartialGraph createGraphFromBuildRules(BuildRuleResolver ruleResolver,
      List<String> targets) {
    List<BuildTarget> buildTargets = Lists.transform(targets, new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String target) {
        return BuildTargetFactory.newInstance(target);
      }
    });

    DependencyGraph dependencyGraph = RuleMap.createGraphFromBuildRules(ruleResolver);
    return PartialGraphFactory.newInstance(dependencyGraph, buildTargets);
  }
}
