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

import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.QueryTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventBusForTests.CapturingConsoleEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.pf4j.PluginManager;

public class BuckQueryEnvironmentTest {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  private BuckQueryEnvironment buckQueryEnvironment;
  private Path cellRoot;
  private ListeningExecutorService executor;
  private PerBuildState parserState;
  private BuckEventBus eventBus;
  private CapturingConsoleEventListener capturingConsoleEventListener;

  private QueryTarget createQueryBuildTarget(String baseName, String shortName) {
    return QueryBuildTarget.of(BuildTargetFactory.newInstance(baseName, shortName));
  }

  @Before
  public void setUp() throws IOException {
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    eventBus = BuckEventBusForTests.newInstance();
    capturingConsoleEventListener = new CapturingConsoleEventListener();
    eventBus.register(capturingConsoleEventListener);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    Cells cell =
        new TestCellBuilder()
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath()))
            .build();

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);

    ExecutableFinder executableFinder = new ExecutableFinder();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ParserConfig parserConfig = cell.getRootCell().getBuckConfig().getView(ParserConfig.class);
    PerBuildStateFactory perBuildStateFactory =
        new PerBuildStateFactory(
            typeCoercerFactory,
            new DefaultConstructorArgMarshaller(),
            knownRuleTypesProvider,
            new ParserPythonInterpreterProvider(parserConfig, executableFinder),
            WatchmanFactory.NULL_WATCHMAN,
            eventBus,
            new ParsingUnconfiguredBuildTargetViewFactory(),
            UnconfiguredTargetConfiguration.INSTANCE);
    Parser parser =
        TestParserFactory.create(
            depsAwareExecutor.get(), cell.getRootCell(), perBuildStateFactory, eventBus);
    parserState =
        perBuildStateFactory.create(
            ParsingContext.builder(cell.getRootCell(), executor)
                .setSpeculativeParsing(SpeculativeParsing.ENABLED)
                .build(),
            parser.getPermState());

    TargetPatternEvaluator targetPatternEvaluator =
        new TargetPatternEvaluator(
            cell.getRootCell(),
            cell.getRootCell().getRoot().getPath(),
            FakeBuckConfig.builder().build(),
            parser,
            ParsingContext.builder(cell.getRootCell(), executor).build(),
            Optional.empty());
    OwnersReport.Builder ownersReportBuilder =
        OwnersReport.builder(
            cell.getRootCell(),
            cell.getRootCell().getRoot().getPath(),
            parser,
            parserState,
            Optional.empty());
    buckQueryEnvironment =
        BuckQueryEnvironment.from(
            cell.getRootCell(),
            ownersReportBuilder,
            parser,
            parserState,
            targetPatternEvaluator,
            eventBus,
            TYPE_COERCER_FACTORY);
    cellRoot = workspace.getDestPath();
  }

  @After
  public void cleanUp() {
    parserState.close();
    executor.shutdown();
  }

  @Test
  public void testResolveSingleTargets() throws QueryException {
    Set<QueryTarget> targets;
    ImmutableSet<QueryTarget> expectedTargets;

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example:six");
    expectedTargets =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare)
            .add(createQueryBuildTarget("//example", "six"))
            .build();
    assertThat(targets, is(equalTo(expectedTargets)));

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example/app:seven");
    expectedTargets =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare)
            .add(createQueryBuildTarget("//example/app", "seven"))
            .build();
    assertThat(targets, is(equalTo(expectedTargets)));
  }

  @Test
  public void testResolveTargetPattern() throws QueryException {
    ImmutableSet<QueryTarget> expectedTargets =
        new ImmutableSortedSet.Builder<>(QueryTarget::compare)
            .add(
                createQueryBuildTarget("//example", "one"),
                createQueryBuildTarget("//example", "two"),
                createQueryBuildTarget("//example", "three"),
                createQueryBuildTarget("//example", "four"),
                createQueryBuildTarget("//example", "five"),
                createQueryBuildTarget("//example", "six"),
                createQueryBuildTarget("//example", "application-test-lib"),
                createQueryBuildTarget("//example", "test-lib-lib"),
                createQueryBuildTarget("//example", "one-tests"),
                createQueryBuildTarget("//example", "four-tests"),
                createQueryBuildTarget("//example", "four-application-tests"),
                createQueryBuildTarget("//example", "six-tests"))
            .build();
    assertThat(
        buckQueryEnvironment.getTargetsMatchingPattern("//example:"), is(equalTo(expectedTargets)));
  }

  @Test
  public void whenNonExistentFileIsQueriedAWarningIsIssued() {
    ImmutableList<String> expectedTargets = ImmutableList.of("/foo/bar");
    buckQueryEnvironment.getFileOwners(expectedTargets);
    String expectedWarning =
        "File " + MorePaths.pathWithPlatformSeparators("/foo/bar") + " does not exist";
    assertThat(
        capturingConsoleEventListener.getLogMessages(),
        CoreMatchers.equalTo(singletonList(expectedWarning)));
  }
}
