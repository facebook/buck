/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.analysis.impl;

import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Location;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                    ImmutableList.of(new FakeRuleRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    assertEquals(ImmutableList.of("testcontent"), Files.readAllLines(resultPath));
  }

  @Test
  public void ruleAnalysisRuleWithDepsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_deps", tmp);

    workspace.setUp();
    workspace.enableDirCache();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new BasicRuleRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs :{}
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs :{}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs :{}
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
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
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()))),
                new RuleOutput("faz", 0, ImmutableList.of(), ImmutableList.of())));

    JsonParser parser = ObjectMappers.createParser(resultPath);
    Map<String, Object> data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));

    // clean
    workspace.runBuckCommand("clean", "--keep-cache");

    // rebuild should be same result and cached
    resultPath = workspace.buildAndReturnOutput("//:bar");
    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:bar");

    // now make a change

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
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs: {}
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs: {}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs: {}
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 10
     *      srcs: {}
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
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()))),
                new RuleOutput("faz", 10, ImmutableList.of(), ImmutableList.of())));

    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);
    parser.close();

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
                    ImmutableList.of(new BasicRuleRuleDescription(), new LegacyRuleDescription()),
                    ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs :{}
     * dep: {
     *    {
     *      target: baz
     *      val: 4
     *      srcs :{}
     *      dep: {}
     *    },
     *    {
     *      target: foo
     *      val: 2
     *      srcs :{}
     *      dep: {
     *        {
     *          target: baz
     *          val: 4
     *          srcs :{}
     *          dep: {}
     *        }
     *      }
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
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
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput("baz", 4, ImmutableList.of(), ImmutableList.of()))),
                new RuleOutput("faz", 0, ImmutableList.of(), ImmutableList.of())));

    JsonParser parser = ObjectMappers.createParser(resultPath);
    Map<String, Object> data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));
  }

  @Test
  public void ruleAnalysisRuleWithTargetSrcsBuildsAndRebuildsOnChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "rule_with_target_srcs", tmp);

    workspace.setUp();
    workspace.enableDirCache();

    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new BasicRuleRuleDescription()), ImmutableList.of()));

    Path resultPath = workspace.buildAndReturnOutput("//:bar");

    BuildArtifactFactoryForTests artifactFactory =
        new BuildArtifactFactoryForTests(
            BuildTargetFactory.newInstance("//:foo"), workspace.getProjectFileSystem());

    Path fooArtifact =
        artifactFactory
            .createBuildArtifact(Paths.get("output"), Location.BUILTIN)
            .getSourcePath()
            .getResolvedPath();

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs: { foo }
     * deps: {}
     * }
     */
    RuleOutput output = new RuleOutput("bar", 1, ImmutableList.of(fooArtifact), ImmutableList.of());

    JsonParser parser = ObjectMappers.createParser(resultPath);
    Map<String, Object> data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));

    // clean
    workspace.runBuckCommand("clean", "--keep-cache");

    // rebuild should be same result and cached
    resultPath = workspace.buildAndReturnOutput("//:bar");
    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:bar");

    // now make a change

    workspace.writeContentsToPath(
        "basic_rule(\n"
            + "    name = \"foo\",\n"
            + "    val = 2,\n"
            + "    visibility = [\"PUBLIC\"],\n"
            + "    outname = \"foo_out\""
            + ")",
        "dir/BUCK");

    resultPath = workspace.buildAndReturnOutput("//:bar");

    Path fooArtifact2 =
        artifactFactory
            .createBuildArtifact(Paths.get("foo_out"), Location.BUILTIN)
            .getSourcePath()
            .getResolvedPath();

    /**
     * we should get something like
     *
     * <pre>
     * {
     * target: bar
     * val: 1
     * srcs: { foo_out }
     * deps: {}
     * }
     */
    output = new RuleOutput("bar", 1, ImmutableList.of(fooArtifact2), ImmutableList.of());

    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));
  }

  private static class RuleOutput {
    final String target;
    final int val;
    final List<Path> srcs;
    final List<RuleOutput> deps;

    private RuleOutput(String target, int val, List<Path> srcs, List<RuleOutput> deps) {
      this.target = target;
      this.val = val;
      this.srcs = srcs;
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
