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

import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.util.List;

public class TestCommandTest {

  private TestCommand getCommand(String... args) throws CmdLineException {
    TestCommand command = new TestCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  @Test
  public void testFilterBuilds() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommand command = getCommand("--exclude", "linux", "windows");

    TestRule rule1 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows"), Label.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    TestRule rule2 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("android")),
        BuildTargetFactory.newInstance("//:teh"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    TestRule rule3 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);

    Iterable<TestRule> result = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.<BuildTarget>of(),
        testRules);
    assertThat(result, contains(rule2));
  }

  @Test
  public void testLabelConjunctionsWithInclude() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommand command = getCommand("--include", "windows+linux");

    TestRule rule1 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows"), Label.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    TestRule rule2 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.<BuildTarget>of(),
        testRules);
    assertEquals(ImmutableSet.of(rule1), result);
  }

  @Test
  public void testLabelConjunctionsWithExclude() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommand command = getCommand("--exclude", "windows+linux");

    TestRule rule1 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows"), Label.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    TestRule rule2 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.<BuildTarget>of(),
        testRules);
    assertEquals(ImmutableSet.of(rule2), result);
  }

  @Test
  public void testLabelPriority() throws CmdLineException {
    TestCommand command = getCommand("--exclude", "c", "--include", "a+b");

    TestRule rule = new FakeTestRule(
        ImmutableSet.<Label>of(
            Label.of("a"),
            Label.of("b"),
            Label.of("c")),
        BuildTargetFactory.newInstance("//:for"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.<BuildTarget>of(),
        testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testLabelPlingSyntax() throws CmdLineException {
    TestCommand command = getCommand("--labels", "!c", "a+b");

    TestRule rule = new FakeTestRule(
        ImmutableSet.<Label>of(
            Label.of("a"),
            Label.of("b"),
            Label.of("c")),
        BuildTargetFactory.newInstance("//:for"),
        new SourcePathResolver(new BuildRuleResolver()),
        ImmutableSortedSet.<BuildRule>of());

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.<BuildTarget>of(),
        testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testNoTransitiveTests() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommand command = getCommand("--exclude-transitive-tests", "//:wow");

    FakeTestRule rule1 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows"), Label.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    FakeTestRule rule2 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule1));

    FakeTestRule rule3 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("linux")),
        BuildTargetFactory.newInstance("//:wow"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule2));

    List<TestRule> testRules = ImmutableList.<TestRule>of(rule1, rule2, rule3);
    Iterable<TestRule> filtered = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.of(BuildTargetFactory.newInstance("//:wow")),
        testRules);

    assertEquals(rule3, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void testNoTransitiveTestsWhenLabelExcludeWins() throws CmdLineException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    TestCommand command = getCommand(
        "--labels", "!linux", "--always-exclude",
        "--exclude-transitive-tests", "//:for", "//:lulz");

    FakeTestRule rule1 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows"), Label.of("linux")),
        BuildTargetFactory.newInstance("//:for"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of());

    FakeTestRule rule2 = new FakeTestRule(
        ImmutableSet.<Label>of(Label.of("windows")),
        BuildTargetFactory.newInstance("//:lulz"),
        pathResolver,
        ImmutableSortedSet.<BuildRule>of(rule1));

    List<TestRule> testRules = ImmutableList.<TestRule>of(rule1, rule2);
    Iterable<TestRule> filtered = command.filterTestRules(
        new FakeBuckConfig(),
        ImmutableSet.of(
            BuildTargetFactory.newInstance("//:for"),
            BuildTargetFactory.newInstance("//:lulz")),
        testRules);

    assertEquals(rule2, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void testIfAGlobalExcludeExcludesALabel() throws CmdLineException {
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.of(
            "test",
            ImmutableMap.of("excluded_labels", "e2e")));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommand command = new TestCommand();

    new AdditionalOptionsCmdLineParser(command).parseArgument();

    assertFalse(command.isMatchedByLabelOptions(config, ImmutableSet.<Label>of(Label.of("e2e"))));
  }

  @Test
  public void testIfALabelIsIncludedItShouldNotBeExcludedEvenIfTheExcludeIsGlobal()
      throws CmdLineException {
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.of(
            "test",
            ImmutableMap.of("excluded_labels", "e2e")));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommand command = new TestCommand();

    new AdditionalOptionsCmdLineParser(command).parseArgument("--include", "e2e");

    assertTrue(command.isMatchedByLabelOptions(config, ImmutableSet.<Label>of(Label.of("e2e"))));
  }

  @Test
  public void testIncludingATestOnTheCommandLineMeansYouWouldLikeItRun() throws CmdLineException {
    String excludedLabel = "exclude_me";
    BuckConfig config = new FakeBuckConfig(
        ImmutableMap.of(
            "test",
            ImmutableMap.of("excluded_labels", excludedLabel)));
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains(excludedLabel));
    TestCommand command = new TestCommand();

    new AdditionalOptionsCmdLineParser(command).parseArgument("//example:test");

    FakeTestRule rule = new FakeTestRule(
        /* labels */ ImmutableSet.<Label>of(Label.of(excludedLabel)),
        BuildTargetFactory.newInstance("//example:test"),
        new SourcePathResolver(new BuildRuleResolver()),
        /* deps */ ImmutableSortedSet.<BuildRule>of()
        /* visibility */);
    Iterable<TestRule> filtered = command.filterTestRules(
        config,
        ImmutableSet.of(BuildTargetFactory.newInstance("//example:test")),
        ImmutableSet.<TestRule>of(rule));

    assertEquals(rule, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void shouldAlwaysDefaultToOneThreadWhenRunningTestsWithDebugFlag()
      throws CmdLineException {
    TestCommand command = getCommand("-j", "15");

    assertThat(
        command.getNumTestThreads(new FakeBuckConfig(command.getConfigOverrides())),
        Matchers.equalTo(15));

    command = getCommand("-j", "15", "--debug");

    assertThat(
        command.getNumTestThreads(new FakeBuckConfig(command.getConfigOverrides())),
        Matchers.equalTo(1));
  }
}
