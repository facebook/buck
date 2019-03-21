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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.cli.OwnersReport.Builder;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.concurrent.FakeListeningExecutorService;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class QueryCommandTest {

  private QueryCommand queryCommand;
  private CommandRunnerParams params;

  private int callsCount = 0;
  private Set<String> expectedExpressions = new HashSet<>();

  private BuckQueryEnvironment env;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      getManifestSupplier() {
    return ThrowingCloseableMemoizedSupplier.of(() -> null, ManifestService::close);
  }

  @Before
  public void setUp() throws IOException {
    TestConsole console = new TestConsole();
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(
            workspace.getDestPath().toRealPath().normalize());
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();

    queryCommand = new QueryCommand();
    queryCommand.outputAttributesSane = Suppliers.ofInstance(ImmutableSet.of());
    params =
        CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            console,
            cell,
            artifactCache,
            eventBus,
            FakeBuckConfig.builder().build(),
            Platform.detect(),
            EnvVariablesProvider.getSystemEnv(),
            new FakeJavaPackageFinder(),
            Optional.empty());

    ListeningExecutorService executorService = new FakeListeningExecutorService();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    PerBuildState perBuildState =
        PerBuildStateFactory.createFactory(
                typeCoercerFactory,
                new DefaultConstructorArgMarshaller(typeCoercerFactory),
                params.getKnownRuleTypesProvider(),
                new ParserPythonInterpreterProvider(cell.getBuckConfig(), new ExecutableFinder()),
                cell.getBuckConfig(),
                WatchmanFactory.NULL_WATCHMAN,
                eventBus,
                getManifestSupplier(),
                new FakeFileHashCache(ImmutableMap.of()),
                new ParsingUnconfiguredBuildTargetFactory())
            .create(
                ParsingContext.builder(cell, executorService)
                    .setSpeculativeParsing(SpeculativeParsing.ENABLED)
                    .build(),
                params.getParser().getPermState(),
                ImmutableList.of());
    env =
        new FakeBuckQueryEnvironment(
            cell,
            OwnersReport.builder(
                params.getCell(),
                params.getParser(),
                perBuildState,
                EmptyTargetConfiguration.INSTANCE),
            params.getParser(),
            perBuildState,
            new TargetPatternEvaluator(
                params.getCell(),
                params.getBuckConfig(),
                params.getParser(),
                ParsingContext.builder(params.getCell(), executorService).build(),
                EmptyTargetConfiguration.INSTANCE),
            eventBus,
            typeCoercerFactory);
  }

  private class FakeBuckQueryEnvironment extends BuckQueryEnvironment {
    protected FakeBuckQueryEnvironment(
        Cell rootCell,
        Builder ownersReportBuilder,
        Parser parser,
        PerBuildState parserState,
        TargetPatternEvaluator targetPatternEvaluator,
        BuckEventBus eventBus,
        TypeCoercerFactory typeCoercerFactory) {
      super(
          rootCell,
          ownersReportBuilder,
          parser,
          parserState,
          targetPatternEvaluator,
          eventBus,
          typeCoercerFactory);
    }

    @Override
    public ImmutableSet<QueryTarget> evaluateQuery(String query) {
      Assert.assertTrue(expectedExpressions.contains(query));
      ++callsCount;
      return ImmutableSet.of();
    }

    @Override
    public void preloadTargetPatterns(Iterable<String> patterns) {}
  }

  @Test
  public void testRunMultiQueryWithSet() throws Exception {
    queryCommand.setArguments(ImmutableList.of("deps(%Ss)", "//foo:bar", "//foo:baz"));
    expectedExpressions.add("deps(set('//foo:bar' '//foo:baz'))");
    queryCommand.formatAndRunQuery(params, env);
  }

  @Test
  public void testRunMultiQueryWithSingleSetUsedMultipleTimes() throws Exception {
    queryCommand.setArguments(
        ImmutableList.of("deps(%Ss) union testsof(%Ss)", "//foo:libfoo", "//foo:libfootoo"));
    expectedExpressions.add(
        "deps(set('//foo:libfoo' '//foo:libfootoo')) union testsof(set('//foo:libfoo' '//foo:libfootoo'))");
    queryCommand.formatAndRunQuery(params, env);
  }

  @Test
  public void testRunMultiQueryWithMultipleDifferentSets() throws Exception {
    queryCommand.setArguments(
        ImmutableList.of(
            "deps(%Ss) union testsof(%Ss)",
            "//foo:libfoo", "//foo:libfootoo", "--", "//bar:libbar", "//bar:libbaz"));
    expectedExpressions.add(
        "deps(set('//foo:libfoo' '//foo:libfootoo')) union testsof(set('//bar:libbar' '//bar:libbaz'))");
    queryCommand.formatAndRunQuery(params, env);
  }

  @Test(expected = HumanReadableException.class)
  public void testRunMultiQueryWithIncorrectNumberOfSets() throws Exception {
    queryCommand.setArguments(
        ImmutableList.of(
            "deps(%Ss) union testsof(%Ss) union %Ss",
            "//foo:libfoo", "//foo:libfootoo", "--", "//bar:libbar", "//bar:libbaz"));
    queryCommand.formatAndRunQuery(params, env);
  }

  @Test
  public void testRunMultiQuery() throws Exception {
    queryCommand.setArguments(ImmutableList.of("deps(%s)", "//foo:bar", "//foo:baz"));
    expectedExpressions.add("deps(//foo:bar)");
    expectedExpressions.add("deps(//foo:baz)");
    queryCommand.formatAndRunQuery(params, env);
    Assert.assertEquals(2, callsCount);
  }
}
