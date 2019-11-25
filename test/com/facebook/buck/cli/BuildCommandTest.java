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

package com.facebook.buck.cli;

import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.BUILT_LOCALLY;
import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.FETCHED_FROM_CACHE;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.MatcherAssert.assertThat;
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
import com.facebook.buck.core.model.targetgraph.ImmutableTargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.MorePaths;
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
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SortedMap;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.pf4j.PluginManager;

public class BuildCommandTest {

  private BuildExecutionResult buildExecutionResult;
  private SourcePathResolverAdapter resolver;
  private Cell rootCell;

  @Before
  public void setUp() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    resolver = graphBuilder.getSourcePathResolver();

    rootCell = new TestCellBuilder().build();

    LinkedHashMap<BuildRule, Optional<BuildResult>> ruleToResult = new LinkedHashMap<>();

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
            "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule1 "
                + "BUILT_LOCALLY "
                + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt"),
            "\u001B[31mFAIL\u001B[0m //fake:rule2",
            "\u001B[1m\u001B[42m\u001B[30mOK  \u001B[0m //fake:rule3 FETCHED_FROM_CACHE",
            "\u001B[31mFAIL\u001B[0m //fake:rule4",
            "",
            " ** Summary of failures encountered during the build **",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some.");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, rootCell)
            .generateForConsole(
                new Console(
                    Verbosity.STANDARD_INFORMATION,
                    new CapturingPrintStream(),
                    new CapturingPrintStream(),
                    Ansi.forceTty()));
    assertEquals(expectedReport, observedReport);
  }

  @Test
  public void testGenerateVerboseBuildReportForConsole() {
    String expectedReport =
        linesToText(
            "OK   //fake:rule1 BUILT_LOCALLY "
                + MorePaths.pathWithPlatformSeparators("buck-out/gen/fake/rule1.txt"),
            "FAIL //fake:rule2",
            "OK   //fake:rule3 FETCHED_FROM_CACHE",
            "FAIL //fake:rule4",
            "",
            " ** Summary of failures encountered during the build **",
            "Rule //fake:rule2 FAILED because java.lang.RuntimeException: some.");
    String observedReport =
        new BuildReport(buildExecutionResult, resolver, rootCell)
            .generateForConsole(new TestConsole(Verbosity.COMMANDS));
    assertEquals(expectedReport, observedReport);
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
            "      \"output\" : " + rule1TxtPath,
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
                BuildTargetFactory.newInstance(buildTargetName), new OutputLabel("label"))));
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
                BuildTargetFactory.newInstance(buildTargetName), new OutputLabel("label1"))));
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
                BuildTargetFactory.newInstance(buildTargetName1), new OutputLabel("label1")),
            ImmutableBuildTargetWithOutputs.of(
                BuildTargetFactory.newInstance(buildTargetName2), new OutputLabel("label2"))));
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
                        new FakeProjectFilesystem(), buildTargetName))));
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
                BuildTargetFactory.newInstance(buildTargetName), OutputLabel.DEFAULT)));
  }

  private CommandRunnerParams createTestParams(ImmutableSet<String> buildTargetNames) {
    TestConsole console = new TestConsole();
    CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
        CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));
    Cell cell = new TestCellBuilder().setFilesystem(new FakeProjectFilesystem()).build();
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
            new ImmutableTargetGraphCreationResult(
                TargetGraph.EMPTY,
                buildTargetNames.stream()
                    .map(BuildTargetFactory::newInstance)
                    .collect(ImmutableSet.toImmutableSet()))));
  }

  private BuildTargetSpec getBuildTargetSpec(String buildTargetName, String label) {
    return BuildTargetSpec.from(
        ImmutableUnconfiguredBuildTargetWithOutputs.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance(
                new FakeProjectFilesystem(), buildTargetName),
            new OutputLabel(label)));
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
