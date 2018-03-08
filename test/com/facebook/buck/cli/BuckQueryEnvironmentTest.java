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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypesFactory;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuckQueryEnvironmentTest {

  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuckQueryEnvironment buckQueryEnvironment;
  private Path cellRoot;
  private ListeningExecutorService executor;
  private PerBuildState parserState;

  private QueryTarget createQueryBuildTarget(String baseName, String shortName) {
    return QueryBuildTarget.of(BuildTargetFactory.newInstance(cellRoot, baseName, shortName));
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    Cell cell =
        new TestCellBuilder()
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath()))
            .build();

    KnownBuildRuleTypesProvider knownBuildRuleTypesProvider =
        KnownBuildRuleTypesProvider.of(
            DefaultKnownBuildRuleTypesFactory.of(
                new DefaultProcessExecutor(new TestConsole()),
                BuckPluginManagerFactory.createPluginManager(),
                new TestSandboxExecutionStrategyFactory()));

    ExecutableFinder executableFinder = new ExecutableFinder();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    Parser parser =
        new Parser(
            new BroadcastEventListener(),
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            knownBuildRuleTypesProvider,
            executableFinder);
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    parserState =
        new PerBuildState(
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory),
            parser.getPermState(),
            eventBus,
            executableFinder,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            /* enableProfiling */ false,
            PerBuildState.SpeculativeParsing.ENABLED);

    TargetPatternEvaluator targetPatternEvaluator =
        new TargetPatternEvaluator(
            cell, FakeBuckConfig.builder().build(), parser, eventBus, /* enableProfiling */ false);
    OwnersReport.Builder ownersReportBuilder = OwnersReport.builder(cell, parser, eventBus);
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    buckQueryEnvironment =
        BuckQueryEnvironment.from(
            cell,
            ownersReportBuilder,
            parserState,
            executor,
            targetPatternEvaluator,
            null /* TODO */,
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
    ImmutableSet<QueryTarget> targets;
    ImmutableSet<QueryTarget> expectedTargets;

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example:six");
    expectedTargets = ImmutableSortedSet.of(createQueryBuildTarget("//example", "six"));
    assertThat(targets, is(equalTo(expectedTargets)));

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example/app:seven");
    expectedTargets = ImmutableSortedSet.of(createQueryBuildTarget("//example/app", "seven"));
    assertThat(targets, is(equalTo(expectedTargets)));
  }

  @Test
  public void testResolveTargetPattern() throws QueryException {
    ImmutableSet<QueryTarget> expectedTargets =
        ImmutableSortedSet.of(
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
            createQueryBuildTarget("//example", "six-tests"));
    assertThat(
        buckQueryEnvironment.getTargetsMatchingPattern("//example:"), is(equalTo(expectedTargets)));
  }
}
