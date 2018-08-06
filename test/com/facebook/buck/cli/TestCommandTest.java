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

import com.facebook.buck.core.cell.name.RelativeCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.rules.FakeTestRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

public class TestCommandTest {

  private TestCommand getCommand(String... args) throws CmdLineException {
    TestCommand command = new TestCommand();
    CmdLineParserFactory.create(command).parseArgument(args);
    return command;
  }

  @Test
  public void testFilterBuilds() throws CmdLineException {
    TestCommand command = getCommand("--exclude", "linux", "windows");

    TestRule rule1 =
        new FakeTestRule(
            ImmutableSet.of("windows", "linux"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    TestRule rule2 =
        new FakeTestRule(
            ImmutableSet.of("android"),
            BuildTargetFactory.newInstance("//:teh"),
            ImmutableSortedSet.of());

    TestRule rule3 =
        new FakeTestRule(
            ImmutableSet.of("windows"),
            BuildTargetFactory.newInstance("//:lulz"),
            ImmutableSortedSet.of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);

    Iterable<TestRule> result =
        command.filterTestRules(FakeBuckConfig.builder().build(), ImmutableSet.of(), testRules);
    assertThat(result, contains(rule2));
  }

  @Test
  public void testLabelConjunctionsWithInclude() throws CmdLineException {
    TestCommand command = getCommand("--include", "windows+linux");

    TestRule rule1 =
        new FakeTestRule(
            ImmutableSet.of("windows", "linux"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    TestRule rule2 =
        new FakeTestRule(
            ImmutableSet.of("windows"),
            BuildTargetFactory.newInstance("//:lulz"),
            ImmutableSortedSet.of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result =
        command.filterTestRules(FakeBuckConfig.builder().build(), ImmutableSet.of(), testRules);
    assertEquals(ImmutableSet.of(rule1), result);
  }

  @Test
  public void testLabelConjunctionsWithExclude() throws CmdLineException {
    TestCommand command = getCommand("--exclude", "windows+linux");

    TestRule rule1 =
        new FakeTestRule(
            ImmutableSet.of("windows", "linux"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    TestRule rule2 =
        new FakeTestRule(
            ImmutableSet.of("windows"),
            BuildTargetFactory.newInstance("//:lulz"),
            ImmutableSortedSet.of());

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);

    Iterable<TestRule> result =
        command.filterTestRules(FakeBuckConfig.builder().build(), ImmutableSet.of(), testRules);
    assertEquals(ImmutableSet.of(rule2), result);
  }

  @Test
  public void testLabelPriority() throws CmdLineException {
    TestCommand command = getCommand("--exclude", "c", "--include", "a+b");

    TestRule rule =
        new FakeTestRule(
            ImmutableSet.of("a", "b", "c"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result =
        command.filterTestRules(FakeBuckConfig.builder().build(), ImmutableSet.of(), testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testLabelPlingSyntax() throws CmdLineException {
    TestCommand command = getCommand("--labels", "!c", "a+b");

    TestRule rule =
        new FakeTestRule(
            ImmutableSet.of("a", "b", "c"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    List<TestRule> testRules = ImmutableList.of(rule);

    Iterable<TestRule> result =
        command.filterTestRules(FakeBuckConfig.builder().build(), ImmutableSet.of(), testRules);
    assertEquals(ImmutableSet.of(), result);
  }

  @Test
  public void testNoTransitiveTests() throws CmdLineException {
    TestCommand command = getCommand("--exclude-transitive-tests", "//:wow");

    FakeTestRule rule1 =
        new FakeTestRule(
            ImmutableSet.of("windows", "linux"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    FakeTestRule rule2 =
        new FakeTestRule(
            ImmutableSet.of("windows"),
            BuildTargetFactory.newInstance("//:lulz"),
            ImmutableSortedSet.of(rule1));

    FakeTestRule rule3 =
        new FakeTestRule(
            ImmutableSet.of("linux"),
            BuildTargetFactory.newInstance("//:wow"),
            ImmutableSortedSet.of(rule2));

    List<TestRule> testRules = ImmutableList.of(rule1, rule2, rule3);
    Iterable<TestRule> filtered =
        command.filterTestRules(
            FakeBuckConfig.builder().build(),
            ImmutableSet.of(BuildTargetFactory.newInstance("//:wow")),
            testRules);

    assertEquals(rule3, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void testNoTransitiveTestsWhenLabelExcludeWins() throws CmdLineException {
    TestCommand command =
        getCommand(
            "--labels",
            "!linux",
            "--always-exclude",
            "--exclude-transitive-tests",
            "//:for",
            "//:lulz");

    FakeTestRule rule1 =
        new FakeTestRule(
            ImmutableSet.of("windows", "linux"),
            BuildTargetFactory.newInstance("//:for"),
            ImmutableSortedSet.of());

    FakeTestRule rule2 =
        new FakeTestRule(
            ImmutableSet.of("windows"),
            BuildTargetFactory.newInstance("//:lulz"),
            ImmutableSortedSet.of(rule1));

    List<TestRule> testRules = ImmutableList.of(rule1, rule2);
    Iterable<TestRule> filtered =
        command.filterTestRules(
            FakeBuckConfig.builder().build(),
            ImmutableSet.of(
                BuildTargetFactory.newInstance("//:for"),
                BuildTargetFactory.newInstance("//:lulz")),
            testRules);

    assertEquals(rule2, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void testIfAGlobalExcludeExcludesALabel() throws CmdLineException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("test", ImmutableMap.of("excluded_labels", "e2e")))
            .build();
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommand command = new TestCommand();

    CmdLineParserFactory.create(command).parseArgument();

    assertFalse(command.isMatchedByLabelOptions(config, ImmutableSet.of("e2e")));
  }

  @Test
  public void testIfALabelIsIncludedItShouldNotBeExcludedEvenIfTheExcludeIsGlobal()
      throws CmdLineException {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("test", ImmutableMap.of("excluded_labels", "e2e")))
            .build();
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains("e2e"));
    TestCommand command = new TestCommand();

    CmdLineParserFactory.create(command).parseArgument("--include", "e2e");

    assertTrue(command.isMatchedByLabelOptions(config, ImmutableSet.of("e2e")));
  }

  @Test
  public void testIncludingATestOnTheCommandLineMeansYouWouldLikeItRun() throws CmdLineException {
    String excludedLabel = "exclude_me";
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("test", ImmutableMap.of("excluded_labels", excludedLabel)))
            .build();
    assertThat(config.getDefaultRawExcludedLabelSelectors(), contains(excludedLabel));
    TestCommand command = new TestCommand();

    CmdLineParserFactory.create(command).parseArgument("//example:test");

    FakeTestRule rule =
        new FakeTestRule(
            /* labels */ ImmutableSet.of(excludedLabel),
            BuildTargetFactory.newInstance("//example:test"),
            /* deps */ ImmutableSortedSet.of()
            /* visibility */ );
    Iterable<TestRule> filtered =
        command.filterTestRules(
            config,
            ImmutableSet.of(BuildTargetFactory.newInstance("//example:test")),
            ImmutableSet.of(rule));

    assertEquals(rule, Iterables.getOnlyElement(filtered));
  }

  @Test
  public void shouldAlwaysDefaultToOneThreadWhenRunningTestsWithDebugFlag()
      throws CmdLineException {
    TestCommand command = getCommand("-j", "15");

    assertThat(
        command.getNumTestThreads(
            FakeBuckConfig.builder()
                .setSections(
                    command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME))
                .build()),
        Matchers.equalTo(15));

    command = getCommand("-j", "15", "--debug");

    assertThat(
        command.getNumTestThreads(
            FakeBuckConfig.builder()
                .setSections(
                    command.getConfigOverrides().getForCell(RelativeCellName.ROOT_CELL_NAME))
                .build()),
        Matchers.equalTo(1));
  }
}
