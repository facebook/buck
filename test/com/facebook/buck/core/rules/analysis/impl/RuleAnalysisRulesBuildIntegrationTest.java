/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.analysis.impl;

import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class RuleAnalysisRulesBuildIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void ruleAnalysisRuleBuilds() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "basic_rule", tmp);

    workspace.setUp();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new FakeRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    assertEquals(ImmutableList.of("testcontent"), Files.readAllLines(resultPath));
  }

  @Test
  public void ruleAnalysisRuleWithDepsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_deps", tmp);

    workspace.setUp();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new BasicRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      dep: {}
     *    },
     *  }
     * }
     * </pre>
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of()),
                new RuleOutput(
                    "foo", 2, ImmutableList.of(new RuleOutput("baz", 4, ImmutableList.of()))),
                new RuleOutput("faz", 0, ImmutableList.of())));

    JsonParser parser = ObjectMappers.createParser(resultPath);
    Map<String, Object> data = parser.readValueAs(Map.class);

    assertThat(data, ruleOutputToMatchers(output));

    workspace.writeContentsToPath(
        "basic_rule(\n"
            + "    name = \"faz\",\n"
            + "    val = 10,\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + ")",
        "dir/BUCK");

    resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 10
     *      dep: {}
     *    },
     *  }
     * }
     * </pre>
     */
    output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of()),
                new RuleOutput(
                    "foo", 2, ImmutableList.of(new RuleOutput("baz", 4, ImmutableList.of()))),
                new RuleOutput("faz", 10, ImmutableList.of())));

    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);

    assertThat(data, ruleOutputToMatchers(output));
  }

  @Test
  public void ruleAnalysisRuleWithLegacyCompatibilityBuilds() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_legacy_deps", tmp);

    workspace.setUp();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new BasicRuleDescription(), new LegacyRuleDescription()),
                    ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      dep: {}
     *    },
     *  }
     * }
     * </pre>
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of()),
                new RuleOutput(
                    "foo", 2, ImmutableList.of(new RuleOutput("baz", 4, ImmutableList.of()))),
                new RuleOutput("faz", 0, ImmutableList.of())));

    JsonParser parser = ObjectMappers.createParser(resultPath);
    Map<String, Object> data = parser.readValueAs(Map.class);

    assertThat(data, ruleOutputToMatchers(output));
  }

  private static class RuleOutput {
    final String target;
    final int val;
    final List<RuleOutput> deps;

    private RuleOutput(String target, int val, List<RuleOutput> deps) {
      this.target = target;
      this.val = val;
      this.deps = deps;
    }
  }

  private Matcher<Map<String, Object>> ruleOutputToMatchers(RuleOutput ruleOutput) {

    Matcher<Map<? extends String, ? extends Object>> targetMatcher =
        Matchers.hasEntry("target", ruleOutput.target);
    Matcher<Map<? extends String, ? extends Object>> valMatcher =
        Matchers.hasEntry("val", ruleOutput.val);

    Matcher<Object> deps =
        (Matcher<Object>)
            (Matcher<? extends Object>)
                Matchers.containsInAnyOrder(
                    Collections2.transform(
                        ruleOutput.deps,
                        d -> (Matcher<? super Object>) (Matcher<?>) ruleOutputToMatchers(d)));

    Matcher<Map<? extends String, ? extends Object>> depMatcher =
        Matchers.hasEntry(Matchers.is("dep"), deps);

    Matcher<? extends Map<? extends String, ? extends Object>> matcher =
        Matchers.allOf(targetMatcher, valMatcher, depMatcher);
    return (Matcher<Map<String, Object>>) matcher;
  }
}
