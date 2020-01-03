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

package com.facebook.buck.cli;

import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.BUILT_LOCALLY;
import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.FETCHED_FROM_CACHE;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.command.BuildExecutionResult;
import com.facebook.buck.command.BuildReport;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.ImmutableBuildTargetWithOutputs;
import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.graph.ActionAndTargetGraphs;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeArg;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.ImmutableTargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.PathReferenceRule;
import com.facebook.buck.core.rules.impl.PathReferenceRuleWithMultipleOutputs;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.resolver.impl.FakeActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.parser.DaemonicParserState;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.BuildTargetSpec;
import com.facebook.buck.parser.spec.ImmutableTargetNodePredicateSpec;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.TrackedRuleKeyCache;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.NoOpCacheStatsTracker;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

public class BuildCommandTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  private BuildExecutionResult buildExecutionResult;
  private SourcePathResolverAdapter resolver;
  private Cell rootCell;
  private TestConsole console;
  private ProjectFilesystem projectFilesystem;
  private RuleKeyCacheScope ruleKeyCacheScope;
  private ActionGraphBuilder graphBuilder;
  private Map<BuildRule, Optional<BuildResult>> ruleToResult;

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    resolver = graphBuilder.getSourcePathResolver();
    rootCell = new TestCellBuilder().build();
    ruleToResult = new LinkedHashMap<>();
    console = new TestConsole();
    projectFilesystem = new FakeProjectFilesystem();
    ruleKeyCacheScope =
        new RuleKeyCacheScope() {
          @Override
          public TrackedRuleKeyCache getCache() {
            return new TrackedRuleKeyCache(
                new DefaultRuleKeyCache<>(), new NoOpCacheStatsTracker());
          }

          @Override
          public void close() {}
        };

    FakeBuildRule rule1 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule1"));
    rule1.setOutputFile("buck-out/gen/fake/rule1.txt");
    graphBuilder.addToIndex(rule1);
    ruleToResult.put(
        rule1, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    BuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule2"));
    BuildResult rule2Failure = BuildResult.failure(rule2, new RuntimeException("some"));
    ruleToResult.put(rule2, Optional.of(rule2Failure));
    graphBuilder.addToIndex(rule2);

    BuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule3"));
    ruleToResult.put(
        rule3,
        Optional.of(
            BuildResult.success(
                rule3, FETCHED_FROM_CACHE, CacheResult.hit("dir", ArtifactCacheMode.dir))));
    graphBuilder.addToIndex(rule3);

    BuildRule rule4 = new FakeBuildRule(BuildTargetFactory.newInstance("//fake:rule4"));
    ruleToResult.put(rule4, Optional.empty());
    graphBuilder.addToIndex(rule4);

    BuildRule rule5 =
        new PathReferenceRuleWithMultipleOutputs(
            BuildTargetFactory.newInstance("//fake:rule5"),
            new FakeProjectFilesystem(),
            Paths.get("default_output"),
            ImmutableMap.of(
                OutputLabel.of("named_1"), ImmutableSet.of(Paths.get("named_output_1"))));
    graphBuilder.addToIndex(rule5);
    ruleToResult.put(
        rule5, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    BuildRule rule6 =
        new PathReferenceRuleWithMultipleOutputs(
            BuildTargetFactory.newInstance("//fake:rule6"),
            new FakeProjectFilesystem(),
            Paths.get("default_single_output"),
            ImmutableMap.of(
                OutputLabel.defaultLabel(),
                ImmutableSet.of(Paths.get("default_output1"), Paths.get("default_output_2")),
                OutputLabel.of("named_1"),
                ImmutableSet.of(Paths.get("named_output_1")),
                OutputLabel.of("named_2"),
                ImmutableSet.of(Paths.get("named_output_2"), Paths.get("named_output_22"))));
    graphBuilder.addToIndex(rule6);
    ruleToResult.put(
        rule6, Optional.of(BuildResult.success(rule1, BUILT_LOCALLY, CacheResult.miss())));

    buildExecutionResult =
        BuildExecutionResult.builder()
            .setResults(ruleToResult)
            .setFailures(ImmutableSet.of(rule2Failure))
            .build();
  }

  @Test
  public void testGenerateBuildReportForConsole() {
    String expectedReport =
        linesToText(
            "(?s)"
                + Pattern.quote(
                    "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 "
                        + "BUILT_LOCALLY "
                        + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")),
            Pattern.quote("\u001B[31mFAIL\u001B[0m //fake:rule2"),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE"),
            Pattern.quote("\u001B[31mFAIL\u001B[0m //fake:rule4"),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule5 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule5[named_1] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6 "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("default_output_2")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_1] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_1")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_2] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_2")),
            Pattern.quote(
                "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule6[named_2] "
                    + "BUILT_LOCALLY "
                    + MorePaths.pathWithPlatformSeparators("named_output_22")),
            "",
            " \\*\\* Summary of failures encountered during the build \\*\\*",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some",
            "\tat .*");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, rootCell)
            .generateForConsole(
                new Console(
                    Verbosity.STANDARD_INFORMATION,
                    new CapturingPrintStream(),
                    new CapturingPrintStream(),
                    Ansi.forceTty()));
    assertThat(observedReport, Matchers.matchesPattern(expectedReport));
  }

  @Test
  public void testGenerateVerboseBuildReportForConsole() {
    String expectedReport =
        linesToText(
            "(?s)OK   //fake:rule1 BUILT_LOCALLY "
                + Pattern.quote(
                    MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt")),
            "FAIL //fake:rule2",
            "OK   //fake:rule3 FETCHED_FROM_CACHE",
            "FAIL //fake:rule4",
            "OK   //fake:rule5 BUILT_LOCALLY default_output",
            "OK   //fake:rule5\\[named_1\\] BUILT_LOCALLY named_output_1",
            "OK   //fake:rule6 BUILT_LOCALLY default_output1",
            "OK   //fake:rule6 BUILT_LOCALLY default_output_2",
            "OK   //fake:rule6\\[named_1\\] BUILT_LOCALLY named_output_1",
            "OK   //fake:rule6\\[named_2\\] BUILT_LOCALLY named_output_2",
            "OK   //fake:rule6\\[named_2\\] BUILT_LOCALLY named_output_22",
            "",
            " \\*\\* Summary of failures encountered during the build \\*\\*",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some",
            "\tat .*");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, rootCell)
            .generateForConsole(new TestConsole(Verbosity.COMMANDS));
    assertThat(observedReport, Matchers.matchesPattern(expectedReport));
  }

  @Test
  public void testGenerateJsonBuildReport() throws IOException {
    String rule1TxtPath =
        ObjectMappers.legacyCreate()
            .valueToTree(MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt"))
            .toString();
    String expectedReport =
        String.join(
            System.lineSeparator(),
            "{",
            "  \"success\" : false,",
            "  \"results\" : {",
            "    \"//fake:rule1\" : {",
            "      \"success\" : true,",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"output\" : " + rule1TxtPath + ",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ " + rule1TxtPath + " ]",
            "      }",
            "    },",
            "    \"//fake:rule2\" : {",
            "      \"success\" : false",
            "    },",
            "    \"//fake:rule3\" : {",
            "      \"success\" : true,",
            "      \"type\" : \"FETCHED_FROM_CACHE\"",
            "    },",
            "    \"//fake:rule4\" : {",
            "      \"success\" : false",
            "    },",
            "    \"//fake:rule5\" : {",
            "      \"success\" : true,",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"output\" : \"default_output\",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ \"default_output\" ],",
            "        \"named_1\" : [ \"named_output_1\" ]",
            "      }",
            "    },",
            "    \"//fake:rule6\" : {",
            "      \"success\" : true,",
            "      \"type\" : \"BUILT_LOCALLY\",",
            "      \"outputs\" : {",
            "        \"DEFAULT\" : [ \"default_output1\", \"default_output_2\" ],",
            "        \"named_1\" : [ \"named_output_1\" ],",
            "        \"named_2\" : [ \"named_output_2\", \"named_output_22\" ]",
            "      }",
            "    }",
            "  },",
            "  \"failures\" : {",
            "    \"//fake:rule2\" : \"java.lang.RuntimeException: some\"",
            "  }",
            "}");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, rootCell).generateJsonBuildReport();
    assertEquals(expectedReport, observedReport);
  }

  @Test
  public void testGenerateJsonBuildReportWithNonExistentDefaultOutput() throws IOException {
    exception.expect(IllegalStateException.class);
    exception.expectMessage(
        "Default output group must exist in path_reference_rule_with_multiple_outputs rule //fake:rule7");

    BuildRule rule7 =
        new PathReferenceRuleWithMultipleOutputs(
            BuildTargetFactory.newInstance("//fake:rule7"),
            new FakeProjectFilesystem(),
            Paths.get("unused"),
            ImmutableMap.of(
                OutputLabel.of("named_1"), ImmutableSet.of(Paths.get("named_output_1")))) {
          @Override
          public ImmutableMap<OutputLabel, ImmutableSortedSet<SourcePath>>
              getSourcePathsByOutputsLabels() {
            Map<OutputLabel, ImmutableSortedSet<SourcePath>> toRemoveDefaultOutput =
                new HashMap<>(super.getSourcePathsByOutputsLabels());
            toRemoveDefaultOutput.remove(OutputLabel.defaultLabel());
            return ImmutableMap.copyOf(toRemoveDefaultOutput);
          }
        };
    graphBuilder.addToIndex(rule7);
    ruleToResult.put(
        rule7, Optional.of(BuildResult.success(rule7, BUILT_LOCALLY, CacheResult.miss())));
    BuildExecutionResult buildResult =
        BuildExecutionResult.builder()
            .setResults(ruleToResult)
            .setFailures(ImmutableSet.of())
            .build();

    new BuildReport(buildResult, resolver, rootCell).generateJsonBuildReport();
  }

  @Test
  public void targetNodeSpecLabelIsPropagated() throws Exception {
    String buildTargetName = "//foo:bar";
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    ImmutableList<TargetNodeSpec> targetNodeSpecs =
        ImmutableList.of(getBuildTargetSpec(buildTargetName, "label"));
    BuildCommand buildCommand =
        new BuildCommand() {
          @Override
          ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
              Cell owningCell,
              Path absoluteClientWorkingDir,
              Iterable<String> targetsAsArgs,
              BuckConfig config) {
            return targetNodeSpecs;
          }
        };

    ImmutableSet<ImmutableBuildTargetWithOutputs> result =
        buildCommand
            .createGraphsAndTargets(
                params, MoreExecutors.newDirectExecutorService(), specs -> specs, Optional.empty())
            .getBuildTargetWithOutputs();
    assertThat(
        result,
        Matchers.contains(
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName), OutputLabel.of("label"))));
  }

  @Test
  public void labelsAreNotRetainedForFilteredTargets() throws Exception {
    String buildTargetName = "//foo:bar";
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    ImmutableList<TargetNodeSpec> targetNodeSpecs =
        ImmutableList.of(
            getBuildTargetSpec(buildTargetName, "label1"),
            getBuildTargetSpec("//foo:filtered", "label2"));
    BuildCommand buildCommand =
        new BuildCommand() {
          @Override
          ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
              Cell owningCell,
              Path absoluteClientWorkingDir,
              Iterable<String> targetsAsArgs,
              BuckConfig config) {
            return targetNodeSpecs;
          }
        };

    ImmutableSet<ImmutableBuildTargetWithOutputs> result =
        buildCommand
            .createGraphsAndTargets(
                params,
                MoreExecutors.newDirectExecutorService(),
                (ImmutableList<TargetNodeSpec> specs) -> specs,
                Optional.empty())
            .getBuildTargetWithOutputs();
    assertThat(
        result,
        Matchers.contains(
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName), OutputLabel.of("label1"))));
  }

  @Test
  public void retainsLabelsForMultipleTargetsIfMultiplePassed() throws Exception {
    String buildTargetName1 = "//foo:bar";
    String buildTargetName2 = "//foo:baz";
    CommandRunnerParams params =
        createTestParams(ImmutableSet.of(buildTargetName1, buildTargetName2));

    ImmutableList<TargetNodeSpec> targetNodeSpecs =
        ImmutableList.of(
            getBuildTargetSpec(buildTargetName1, "label1"),
            getBuildTargetSpec("//foo:filtered", "label3"),
            getBuildTargetSpec(buildTargetName2, "label2"));
    BuildCommand buildCommand =
        new BuildCommand() {
          @Override
          ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
              Cell owningCell,
              Path absoluteClientWorkingDir,
              Iterable<String> targetsAsArgs,
              BuckConfig config) {
            return targetNodeSpecs;
          }
        };

    ImmutableSet<ImmutableBuildTargetWithOutputs> result =
        buildCommand
            .createGraphsAndTargets(
                params,
                MoreExecutors.newDirectExecutorService(),
                (ImmutableList<TargetNodeSpec> specs) -> specs,
                Optional.empty())
            .getBuildTargetWithOutputs();
    assertThat(
        result,
        Matchers.contains(
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName1), OutputLabel.of("label1")),
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName2), OutputLabel.of("label2"))));
  }

  @Test
  public void nonTargetNodeSpecDoesNotHaveLabel() throws Exception {
    String buildTargetName = "//foo:foo";
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    ImmutableList<TargetNodeSpec> targetNodeSpecs =
        ImmutableList.of(
            ImmutableTargetNodePredicateSpec.of(
                BuildFileSpec.fromUnconfiguredBuildTarget(
                    UnconfiguredBuildTargetFactoryForTests.newInstance(
                        projectFilesystem, buildTargetName))));
    BuildCommand buildCommand =
        new BuildCommand() {
          @Override
          ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
              Cell owningCell,
              Path absoluteClientWorkingDir,
              Iterable<String> targetsAsArgs,
              BuckConfig config) {
            return targetNodeSpecs;
          }
        };

    ImmutableSet<ImmutableBuildTargetWithOutputs> result =
        buildCommand
            .createGraphsAndTargets(
                params,
                MoreExecutors.newDirectExecutorService(),
                (ImmutableList<TargetNodeSpec> specs) -> specs,
                Optional.empty())
            .getBuildTargetWithOutputs();
    assertThat(
        result,
        Matchers.contains(
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName), OutputLabel.defaultLabel())));
  }

  @Test
  public void showOutputWithoutOutputLabelForRuleThatSupportsMultipleOutputs() throws Exception {
    Path expected = Paths.get("path, timefordinner");
    String buildTargetName = "//foo:foo";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, "")),
            expected,
            ImmutableMap.of(buildTargetName, ImmutableMap.of()),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo", expected))));
  }

  @Test
  public void showOutputWithoutOutputLabelForRuleThatDoesNotSupportMultipleOutputs()
      throws Exception {
    Path expected = Paths.get("path, timefordinner");
    String buildTargetName = "//foo:foo";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, "")),
            expected,
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(OutputLabel.defaultLabel(), ImmutableSet.of(Paths.get("unused")))),
            false);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo", expected))));
  }

  @Test
  public void showOutputWithOutputLabel() throws Exception {
    Path expected = Paths.get("path, timeforlunch");
    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(
                buildTargetName, ImmutableMap.of(OutputLabel.of(label), ImmutableSet.of(expected))),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo[label]", expected))));
  }

  @Test
  public void showOutputsWithOutputLabel() throws Exception {
    Path expected = Paths.get("path, timeforlunch");
    String buildTargetName = "//foo:foo";
    String label = "label";
    Path expected2 = Paths.get("path, timeforsnacc");
    String buildTargetName2 = "//bar:bar";
    String label2 = "label2";

    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label), new Pair(buildTargetName2, label2)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(OutputLabel.of(label), ImmutableSet.of(expected)),
                buildTargetName2,
                ImmutableMap.of(OutputLabel.of(label2), ImmutableSet.of(expected2))),
            true);
    CommandRunnerParams params =
        createTestParams(ImmutableSet.of(buildTargetName, buildTargetName2));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(
            getExpectedShowOutputsLog(
                ImmutableMap.of("//foo:foo[label]", expected, "//bar:bar[label2]", expected2))));
  }

  @Test
  public void showDefaultOutputsIfRuleHasMultipleOutputsAndNoLabelSpecified() throws Exception {
    Path expected = Paths.get("path, defaultpath");
    String buildTargetName = "//foo:foo";
    String label = "label";
    String label2 = "label2";
    String buildTargetName2 = "//bar:bar";
    String label3 = "label3";

    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, "")),
            expected,
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(
                    OutputLabel.of(label),
                    ImmutableSet.of(Paths.get("path, timeforlunch")),
                    OutputLabel.of(label2),
                    ImmutableSet.of(Paths.get("path, timeforsnacc"))),
                buildTargetName2,
                ImmutableMap.of(
                    OutputLabel.of(label3), ImmutableSet.of(Paths.get("path, timefornoms")))),
            true);
    CommandRunnerParams params =
        createTestParams(ImmutableSet.of(buildTargetName, buildTargetName2));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo", expected))));
  }

  @Test
  public void failsIfShowOutputsFlagNotUsedForOutputLabel() throws Exception {
    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        containsString(
            "path_reference_rule_with_multiple_outputs target //foo:foo[label] should use --show-outputs"));

    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(
                    OutputLabel.of(label), ImmutableSet.of(Paths.get("path, timeforlunch")))),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-output");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
  }

  @Test
  public void defaultPathUsedForMultipleOutputRuleWithoutShowOutputs() throws Exception {
    Path expected = Paths.get("path, correctPath");
    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, "")),
            Paths.get("path, correctPath"),
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(
                    OutputLabel.of(label), ImmutableSet.of(Paths.get("path, timeforlunch")))),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-output");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo", expected))));
  }

  @Test
  public void onlyShowOutputForRequestedLabel() throws Exception {
    Path expected = Paths.get("path, timeforlunch");
    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(
                    OutputLabel.of("unrequestedLabel"),
                    ImmutableSet.of(Paths.get("path, nottimeforlunch")),
                    OutputLabel.of(label),
                    ImmutableSet.of(expected))),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(
        console.getTextWrittenToStdOut(),
        Matchers.equalTo(getExpectedShowOutputsLog(ImmutableMap.of("//foo:foo[label]", expected))));
  }

  @Test
  public void shouldThrowIfRequestOutputWithNonDefaultLabelOnRuleThatDoesNotSupportMultipleOutputs()
      throws Exception {
    exception.expect(IllegalStateException.class);
    exception.expectMessage(
        "Multiple outputs not supported for path_reference_rule target //foo:foo");
    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(
                buildTargetName,
                ImmutableMap.of(
                    OutputLabel.of(label), ImmutableSet.of(Paths.get("path, timeforlunch")))),
            false);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-output");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
  }

  @Test
  public void doesNotDieIfCannotFindOutputPath() throws Exception {
    String buildTargetName = "//foo:foo";
    String label = "label";
    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, label)),
            Paths.get("path, wrongpath"),
            ImmutableMap.of(buildTargetName, ImmutableMap.of()),
            true);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-outputs");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(console.getTextWrittenToStdOut(), Matchers.equalTo("//foo:foo[label]\n"));
  }

  @Test
  public void doesNotPrintExtraSpaceIfOutputPathIsEmpty() throws Exception {
    String buildTargetName = "//foo:foo";

    BuildCommand.GraphsAndBuildTargets graphsAndBuildTargets =
        getGraphsAndBuildTargets(
            ImmutableSet.of(new Pair(buildTargetName, "")),
            Paths.get(""),
            ImmutableMap.of(buildTargetName, ImmutableMap.of()),
            false);
    CommandRunnerParams params = createTestParams(ImmutableSet.of(buildTargetName));

    BuildCommand command = getCommand("--show-output");
    command.processSuccessfulBuild(params, graphsAndBuildTargets, ruleKeyCacheScope);
    assertThat(console.getTextWrittenToStdOut(), Matchers.equalTo("//foo:foo\n"));
  }

  private String getExpectedShowOutputsLog(ImmutableMap<String, Path> expectedTargetNamesToPaths) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Path> expected : expectedTargetNamesToPaths.entrySet()) {
      sb.append(String.format("%s %s\n", expected.getKey(), expected.getValue()));
    }
    return sb.toString();
  }

  private CommandRunnerParams createTestParams(ImmutableSet<String> buildTargetNames) {
    CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
        CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));
    Cell cell = new TestCellBuilder().setFilesystem(projectFilesystem).build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);

    return CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
        executor.get(),
        console,
        cell,
        artifactCache,
        eventBus,
        FakeBuckConfig.builder().build(),
        Platform.detect(),
        EnvVariablesProvider.getSystemEnv(),
        new FakeJavaPackageFinder(),
        Optional.empty(),
        pluginManager,
        knownRuleTypesProvider,
        new TestParser(
            TestParserFactory.create(executor.get(), cell, knownRuleTypesProvider),
            ImmutableTargetGraphCreationResult.of(
                TargetGraph.EMPTY,
                buildTargetNames.stream()
                    .map(BuildTargetFactory::newInstance)
                    .collect(ImmutableSet.toImmutableSet()))));
  }

  private BuildTargetSpec getBuildTargetSpec(String buildTargetName, String label) {
    return BuildTargetSpec.from(
        ImmutableUnconfiguredBuildTargetWithOutputs.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance(projectFilesystem, buildTargetName),
            OutputLabel.of(label)));
  }

  private TargetNode<FakeTargetNodeArg> getTargetNode(BuildTarget target) {
    return FakeTargetNodeBuilder.newBuilder(new FakeTargetNodeBuilder.FakeDescription(), target)
        .build();
  }

  private TargetGraph getTargetGraph(Collection<BuildTarget> targets) {
    return TargetGraphFactory.newInstance(
        targets.stream().map(this::getTargetNode).collect(ImmutableSet.toImmutableSet()));
  }

  private ActionAndTargetGraphs getActionAndTargetGraphs(
      TargetGraph targetGraph,
      ImmutableSet<ImmutableBuildTargetWithOutputs> buildTargetsWithOutputs,
      Path defaultPath,
      ImmutableMap<String, ImmutableMap<OutputLabel, ImmutableSet<Path>>> pathsByLabelsForTargets,
      boolean useMultipleOutputsRule) {
    TargetGraphCreationResult targetGraphCreationResult =
        ImmutableTargetGraphCreationResult.of(
            targetGraph,
            buildTargetsWithOutputs.stream()
                .map(ImmutableBuildTargetWithOutputs::getBuildTarget)
                .collect(ImmutableSet.toImmutableSet()));
    ActionGraphAndBuilder actionGraphAndBuilder =
        createActionGraph(
            targetGraph, defaultPath, pathsByLabelsForTargets, useMultipleOutputsRule);
    return ActionAndTargetGraphs.builder()
        .setUnversionedTargetGraph(targetGraphCreationResult)
        .setVersionedTargetGraph(targetGraphCreationResult)
        .setActionGraphAndBuilder(actionGraphAndBuilder)
        .build();
  }

  private BuildCommand.GraphsAndBuildTargets getGraphsAndBuildTargets(
      ImmutableSet<Pair<String, String>> targetNamesWithLabels,
      Path defaultPath,
      ImmutableMap<String, ImmutableMap<OutputLabel, ImmutableSet<Path>>> pathsByLabelsForTargets,
      boolean useMultipleOutputsRule) {
    ImmutableMap.Builder<ImmutableBuildTargetWithOutputs, BuildTarget> builder =
        new ImmutableMap.Builder<>();
    for (Pair<String, String> targetNameWithLabel : targetNamesWithLabels) {
      BuildTarget target = BuildTargetFactory.newInstance(targetNameWithLabel.getFirst());
      builder.put(
          ImmutableBuildTargetWithOutputs.of(
              target,
              targetNameWithLabel.getSecond().isEmpty()
                  ? OutputLabel.defaultLabel()
                  : OutputLabel.of(targetNameWithLabel.getSecond())),
          target);
    }

    ImmutableMap<ImmutableBuildTargetWithOutputs, BuildTarget> targetsByTargetsWithOutputs =
        builder.build();
    TargetGraph targetGraph =
        getTargetGraph(ImmutableSet.copyOf(targetsByTargetsWithOutputs.values()));
    ActionAndTargetGraphs actionAndTargetGraphs =
        getActionAndTargetGraphs(
            targetGraph,
            targetsByTargetsWithOutputs.keySet(),
            defaultPath,
            pathsByLabelsForTargets,
            useMultipleOutputsRule);
    return ImmutableGraphsAndBuildTargets.of(
        actionAndTargetGraphs, targetsByTargetsWithOutputs.keySet());
  }

  private ActionGraphAndBuilder createActionGraph(
      TargetGraph targetGraph,
      Path defaultPath,
      ImmutableMap<String, ImmutableMap<OutputLabel, ImmutableSet<Path>>> pathsByLabelsForTargets,
      boolean useMultipleOutputsRule) {
    ImmutableMap.Builder<BuildTarget, BuildRule> builder = new ImmutableMap.Builder<>();
    for (String targetName : pathsByLabelsForTargets.keySet()) {
      BuildTarget target = BuildTargetFactory.newInstance(targetName);
      builder.put(
          target,
          useMultipleOutputsRule
              ? new PathReferenceRuleWithMultipleOutputs(
                  target, projectFilesystem, defaultPath, pathsByLabelsForTargets.get(targetName))
              : new PathReferenceRule(target, projectFilesystem, defaultPath));
    }
    ActionGraphBuilder actionGraphBuilder =
        new FakeActionGraphBuilder(targetGraph, builder.build());
    ActionGraphAndBuilder actionGraphAndBuilder =
        ActionGraphAndBuilder.of(new ActionGraph(ImmutableSet.of()), actionGraphBuilder);
    return actionGraphAndBuilder;
  }

  private BuildCommand getCommand(String... args) throws CmdLineException {
    BuildCommand command = new BuildCommand();
    CmdLineParserFactory.create(command).parseArgument(args);
    return command;
  }

  /**
   * {@link Parser} that delegates all methods except {@link
   * #buildTargetGraphWithoutTopLevelConfigurationTargets} to a parser for tests.
   */
  private static class TestParser implements Parser {
    private final Parser parser;
    private final TargetGraphCreationResult targetGraphCreationResult;

    private TestParser(Parser parser, TargetGraphCreationResult targetGraphCreationResult) {
      this.parser = parser;
      this.targetGraphCreationResult = targetGraphCreationResult;
    }

    @Override
    public ImmutableList<TargetNode<?>> getAllTargetNodes(
        PerBuildState perBuildState,
        Cell cell,
        Path buildFile,
        Optional<TargetConfiguration> targetConfiguration)
        throws BuildFileParseException {
      return parser.getAllTargetNodes(perBuildState, cell, buildFile, targetConfiguration);
    }

    @Override
    public ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
        PerBuildState state,
        Cell cell,
        Path buildFile,
        Optional<TargetConfiguration> targetConfiguration)
        throws BuildFileParseException {
      return parser.getAllTargetNodesWithTargetCompatibilityFiltering(
          state, cell, buildFile, targetConfiguration);
    }

    @Override
    public DaemonicParserState getPermState() {
      return parser.getPermState();
    }

    @Override
    public PerBuildStateFactory getPerBuildStateFactory() {
      return parser.getPerBuildStateFactory();
    }

    @Override
    public TargetNode<?> getTargetNode(
        ParsingContext parsingContext, BuildTarget target, DependencyStack dependencyStack)
        throws BuildFileParseException {
      return parser.getTargetNode(parsingContext, target, dependencyStack);
    }

    @Override
    public TargetNode<?> getTargetNode(
        PerBuildState perBuildState, BuildTarget target, DependencyStack dependencyStack)
        throws BuildFileParseException {
      return parser.getTargetNode(perBuildState, target, dependencyStack);
    }

    @Override
    public ListenableFuture<TargetNode<?>> getTargetNodeJob(
        PerBuildState perBuildState, BuildTarget target, DependencyStack dependencyStack)
        throws BuildTargetException {
      return parser.getTargetNodeJob(perBuildState, target, dependencyStack);
    }

    @Nullable
    @Override
    public SortedMap<String, Object> getTargetNodeRawAttributes(
        PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
        throws BuildFileParseException {
      return parser.getTargetNodeRawAttributes(state, cell, targetNode, dependencyStack);
    }

    @Override
    public ListenableFuture<SortedMap<String, Object>> getTargetNodeRawAttributesJob(
        PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
        throws BuildFileParseException {
      return parser.getTargetNodeRawAttributesJob(state, cell, targetNode, dependencyStack);
    }

    @Nullable
    @Override
    public SortedMap<String, Object> getTargetNodeRawAttributes(
        ParsingContext parsingContext, TargetNode<?> targetNode, DependencyStack dependencyStack)
        throws BuildFileParseException {
      return parser.getTargetNodeRawAttributes(parsingContext, targetNode, dependencyStack);
    }

    @Override
    public TargetGraphCreationResult buildTargetGraph(
        ParsingContext parsingContext, ImmutableSet<BuildTarget> toExplore)
        throws IOException, InterruptedException, BuildFileParseException {
      return parser.buildTargetGraph(parsingContext, toExplore);
    }

    @Override
    public TargetGraphCreationResult buildTargetGraphWithoutTopLevelConfigurationTargets(
        ParsingContext parsingContext,
        Iterable<? extends TargetNodeSpec> targetNodeSpecs,
        Optional<TargetConfiguration> targetConfiguration)
        throws BuildFileParseException {
      return targetGraphCreationResult;
    }

    @Override
    public TargetGraphCreationResult buildTargetGraphWithTopLevelConfigurationTargets(
        ParsingContext parsingContext,
        Iterable<? extends TargetNodeSpec> targetNodeSpecs,
        Optional<TargetConfiguration> targetConfiguration)
        throws BuildFileParseException, IOException, InterruptedException {
      return parser.buildTargetGraphWithTopLevelConfigurationTargets(
          parsingContext, targetNodeSpecs, targetConfiguration);
    }

    @Override
    public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
        ParsingContext parsingContext,
        Iterable<? extends TargetNodeSpec> specs,
        Optional<TargetConfiguration> targetConfiguration)
        throws BuildFileParseException, InterruptedException {
      return parser.resolveTargetSpecs(parsingContext, specs, targetConfiguration);
    }
  }
}
