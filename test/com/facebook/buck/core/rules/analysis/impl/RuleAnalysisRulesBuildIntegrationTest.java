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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.BuildArtifactFactoryForTests;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
                    ImmutableList.of(new FakeRuleRuleDescription()),
                    ImmutableList.of(),
                    ImmutableList.of()));

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
                    ImmutableList.of(new BasicRuleRuleDescription()),
                    ImmutableList.of(),
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
     *      outputs: [ <hash>/baz__/output ]
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
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
     *      dep: {}
     *      outputs: [ <hash>/faz__/output ]
     *    },
     *  }
     *  outputs: [ <hash>/bar__/output ]
     * }
     * </pre>
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    0,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("dir", "faz__", "output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

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
     *      outputs: [ <hash>/baz__/output ]
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
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 10
     *      srcs: {}
     *      dep: {}
     *      outputs: [ <hash>/faz__/output ]
     *    },
     *  }
     *  outputs: [ <hash>/bar__/output ]
     * }
     * </pre>
     */
    output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(),
            ImmutableList.of(
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    10,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("dir/faz__", "output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

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
                    ImmutableList.of(),
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
     *      outputs: [ <hash>/baz__/output ]
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
     *          outputs: [ <hash>/baz__/output ]
     *        }
     *      }
     *      outputs: [ <hash>/foo__/output ]
     *    },
     *    {
     *      target: faz
     *      val: 0
     *      srcs :{}
     *      dep: {}
     *    },
     *    outputs: [ <hash>/bar__/output ]
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
                new RuleOutput(
                    "baz",
                    4,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("baz__", "output"))),
                new RuleOutput(
                    "foo",
                    2,
                    ImmutableList.of(),
                    ImmutableList.of(
                        new RuleOutput(
                            "baz",
                            4,
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(Paths.get("baz__", "output")))),
                    ImmutableList.of(Paths.get("foo__", "output"))),
                new RuleOutput(
                    "faz",
                    0,
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(Paths.get("output")))),
            ImmutableList.of(Paths.get("bar__", "output")));

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
                    ImmutableList.of(new BasicRuleRuleDescription()),
                    ImmutableList.of(),
                    ImmutableList.of()));

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
     * outputs: [ <hash>/bar__/output ]
     * }
     */
    RuleOutput output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(fooArtifact),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("bar__", "output")));

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
            + "    default_outs = [ \"foo_out\" ],"
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
     * outputs: [ <hash>/bar__/output ]
     * }
     */
    output =
        new RuleOutput(
            "bar",
            1,
            ImmutableList.of(fooArtifact2),
            ImmutableList.of(),
            ImmutableList.of(Paths.get("bar__", "output")));

    parser = ObjectMappers.createParser(resultPath);
    data = parser.readValueAs(Map.class);
    parser.close();

    assertThat(data, ruleOutputToMatchers(output));
  }

  @Test
  public void ruleAnalysisRulesReturningRunInfoCanBeRun() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "runnable_rules", tmp);

    workspace.addBuckConfigLocalOption("parser", "default_build_file_syntax", "skylark");
    workspace.addBuckConfigLocalOption("parser", "user_defined_rules", "enabled");
    workspace.addBuckConfigLocalOption("rule_analysis", "mode", "PROVIDER_COMPATIBLE");

    String successTarget =
        Platform.detect().getType().isWindows()
            ? "//:runnable_bat_success"
            : "//:runnable_sh_success";
    String failureTarget =
        Platform.detect().getType().isWindows()
            ? "//:runnable_bat_failure"
            : "//:runnable_sh_failure";

    String rootString = workspace.getProjectFileSystem().getRootPath().toAbsolutePath().toString();
    String expected =
        Joiner.on(System.lineSeparator())
            .join(
                ImmutableList.of(
                    "pwd: " + rootString,
                    "ENV: some-string",
                    "EXIT_CODE: %s",
                    "arg[foo]",
                    "arg[1]",
                    "arg[//foo:bar]"))
            .trim();

    workspace.setUp();
    ProcessResult successRun = workspace.runBuckCommand("run", successTarget).assertSuccess();
    // Note we don't look at more specific exit codes because the integration framework makes this
    // harder to do.
    ProcessResult failureRun = workspace.runBuckCommand("run", failureTarget).assertFailure();

    assertEquals(String.format(expected, "0"), successRun.getStdout().trim());
    assertEquals(String.format(expected, "100"), failureRun.getStdout().trim());
  }

  @Test
  public void ruleAnalysisRulesReturningTestInfoRunWithInternalRunner() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testable_rules", tmp);

    String successTarget =
        Platform.detect().getType().isWindows()
            ? "//:testable_bat_success"
            : "//:testable_sh_success";
    String failureTarget =
        Platform.detect().getType().isWindows()
            ? "//:testable_bat_failure"
            : "//:testable_sh_failure";

    workspace.setUp();

    Path successJson =
        workspace
            .getProjectFileSystem()
            .createTempFile("", "test-results-success.json")
            .toAbsolutePath();
    Path failureJson =
        workspace
            .getProjectFileSystem()
            .createTempFile("", "test-results-failure.json")
            .toAbsolutePath();

    workspace
        .runBuckCommand("test", successTarget, "--output-test-events-to-file=" + successJson)
        .assertSuccess();

    workspace
        .runBuckCommand("test", failureTarget, "--output-test-events-to-file=" + failureJson)
        .assertTestFailure();

    validateTestResults(
        workspace,
        successJson,
        successTarget,
        true,
        ImmutableList.of("foo", "bar"),
        ImmutableList.of("foo@example.com", "bar@example.com"),
        "testable_rule_test",
        BuildTargetFactory.newInstance(successTarget).getShortName(),
        0);
    validateTestResults(
        workspace,
        failureJson,
        failureTarget,
        false,
        ImmutableList.of("foo", "bar"),
        ImmutableList.of("foo@example.com", "bar@example.com"),
        "testable_rule_test",
        BuildTargetFactory.newInstance(failureTarget).getShortName(),
        100);
  }

  private void validateTestResults(
      ProjectWorkspace workspace,
      Path eventJsonPath,
      String expectedTarget,
      boolean expectedSuccess,
      ImmutableList<String> labels,
      ImmutableList<String> contacts,
      String testName,
      String testCaseName,
      int exitCode)
      throws IOException {

    ImmutableList<JsonNode> failureResults = parseTestResults(eventJsonPath);

    // Useful deserializers aren't present for these events.... bleh.
    assertEquals(1, failureResults.size());
    JsonNode failureResult = failureResults.get(0).get("results");

    BuildTarget actualTarget =
        BuildTargetFactory.newInstance(
            failureResult.get("buildTarget").get("baseName").asText(),
            failureResult.get("buildTarget").get("shortName").asText());

    assertEquals(BuildTargetFactory.newInstance(expectedTarget), actualTarget);

    assertEquals(expectedSuccess, failureResult.get("success").asBoolean());
    assertEquals(expectedSuccess ? 0 : 1, failureResult.get("failureCount").asInt());
    assertEquals(1, failureResult.get("totalNumberOfTests").asInt());
    assertEquals(
        contacts,
        ImmutableList.copyOf(failureResult.get("contacts").elements()).stream()
            .map(JsonNode::asText)
            .collect(ImmutableList.toImmutableList()));
    assertEquals(
        labels,
        ImmutableList.copyOf(failureResult.get("labels").elements()).stream()
            .map(JsonNode::asText)
            .collect(ImmutableList.toImmutableList()));

    JsonNode testCase = failureResult.get("testCases").get(0);
    assertEquals(expectedTarget, testCase.get("testCaseName").asText());
    assertEquals(0, testCase.get("skippedCount").asInt());
    assertEquals(expectedSuccess ? 0 : 1, testCase.get("failureCount").asInt());
    assertEquals(expectedSuccess, testCase.get("success").asBoolean());

    TestResultSummary result =
        ObjectMappers.READER.treeToValue(
            testCase.get("testResults").get(0), TestResultSummary.class);
    assertEquals(testCaseName, result.getTestCaseName());
    assertEquals(expectedSuccess ? ResultType.SUCCESS : ResultType.FAILURE, result.getType());
    assertEquals(testName, result.getTestName());

    String rootString = workspace.getProjectFileSystem().getRootPath().toAbsolutePath().toString();
    ImmutableList<String> expected =
        ImmutableList.of(
            "PWD: " + rootString,
            "pwd: " + rootString,
            "ENV: some-string",
            "EXIT_CODE: " + exitCode,
            "arg[foo]",
            "arg[1]",
            "arg[//foo:bar]");

    assertEquals(expected, ImmutableList.copyOf(result.getStdOut().trim().split("\\r?\n")));
  }

  private ImmutableList<JsonNode> parseTestResults(Path pathToJson) throws IOException {
    return Files.readAllLines(pathToJson, Charsets.UTF_8).stream()
        .map(
            line -> {
              try {
                return ObjectMappers.READER.readTree(line);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .filter(node -> node.get("type").asText().equals("ResultsAvailable"))
        .collect(ImmutableList.toImmutableList());
  }

  @Test
  public void ruleAnalysisRulesReturningTestInfoRunWithExternalRunner() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testable_rules", tmp);
    String testRunner =
        workspace
            .resolve(Platform.detect().getType().isWindows() ? "runner.bat" : "runner.sh")
            .toAbsolutePath()
            .toString();
    workspace.addBuckConfigLocalOption("test", "external_runner", testRunner);

    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:testable_sh_success");

    ProcessResult successRun =
        workspace.runBuckCommand("test", target.getFullyQualifiedName()).assertSuccess();

    ExternalTestRunnerTestSpec spec =
        ExternalTestRunnerTestSpec.builder()
            .setTarget(target)
            .setType("json")
            .setCwd(tmp.getRoot())
            .setEnv(ImmutableMap.of("CUSTOM_ENV", "some-string", "EXIT_CODE", "0"))
            .setCommand(
                ImmutableList.of(
                    tmp.getRoot()
                        .resolve(
                            BuildPaths.getGenDir(workspace.getProjectFileSystem(), target)
                                .resolve("testable.sh"))
                        .toString(),
                    "foo",
                    "1",
                    "//foo:bar"))
            .setLabels(ImmutableList.of("foo", "bar"))
            .setContacts(ImmutableList.of("foo@example.com", "bar@example.com"))
            .build();
    JsonNode expected =
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(spec));

    JsonNode allTests = ObjectMappers.READER.readTree(successRun.getStdout());

    assertTrue(allTests.isArray());
    assertEquals(1, allTests.size());
    assertEquals(expected, allTests.get(0));
  }

  @Test
  public void ruleAnalysisRulesReturningTestInfoWithoutRunInfoAreErrors()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "testable_rules", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckTest("//:without_run").assertFailure();
    assertThat(result.getStderr(), Matchers.containsString("did not return a RunInfo object"));
  }

  private static class RuleOutput {
    final String target;
    final int val;
    final List<Path> srcs;
    final List<RuleOutput> deps;
    final List<Path> outputs;

    private RuleOutput(
        String target, int val, List<Path> srcs, List<RuleOutput> deps, List<Path> outputs) {
      this.target = target;
      this.val = val;
      this.srcs = srcs;
      this.deps = deps;
      this.outputs = outputs;
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
    Matcher<Object> outputs =
        (Matcher<Object>)
            (Matcher<? extends Object>)
                Matchers.containsInAnyOrder(
                    Collections2.transform(
                        ruleOutput.outputs,
                        o ->
                            (Matcher<? super Object>)
                                (Matcher<?>) Matchers.endsWith(o.toString())));

    Matcher<Map<? extends String, ? extends Object>> depMatcher =
        Matchers.hasEntry(Matchers.is("dep"), deps);
    Matcher<Map<? extends String, ? extends Object>> outputsMatcher =
        Matchers.hasEntry(Matchers.is("outputs"), outputs);

    Matcher<? extends Map<? extends String, ? extends Object>> matcher =
        Matchers.allOf(targetMatcher, valMatcher, depMatcher, outputsMatcher);
    return (Matcher<Map<String, Object>>) matcher;
  }
}
