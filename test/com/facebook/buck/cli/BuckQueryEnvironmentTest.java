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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
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

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuckQueryEnvironment buckQueryEnvironment;
  private Path cellRoot;
  private ListeningExecutorService executor;
  private PerBuildState parserState;

  private QueryTarget createQueryBuildTarget(String baseName, String shortName) {
    return QueryBuildTarget.of(BuildTarget.builder(cellRoot, baseName, shortName).build());
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query_command", tmp);
    workspace.setUp();
    Cell cell =
        new TestCellBuilder().setFilesystem(new ProjectFilesystem(workspace.getDestPath())).build();

    TestConsole console = new TestConsole();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    Parser parser =
        new Parser(
            new BroadcastEventListener(),
            cell.getBuckConfig().getView(ParserConfig.class),
            typeCoercerFactory,
            new ConstructorArgMarshaller(typeCoercerFactory));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    parserState =
        new PerBuildState(
            parser,
            eventBus,
            executor,
            cell,
            /* enableProfiling */ false,
            SpeculativeParsing.of(true));

    TargetPatternEvaluator targetPatternEvaluator =
        new TargetPatternEvaluator(
            cell, FakeBuckConfig.builder().build(), parser, eventBus, /* enableProfiling */ false);
    OwnersReport.Builder ownersReportBuilder =
        OwnersReport.builder(cell, parser, eventBus, console);
    buckQueryEnvironment =
        BuckQueryEnvironment.from(cell, ownersReportBuilder, parserState, targetPatternEvaluator);
    cellRoot = workspace.getDestPath();
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
  }

  @After
  public void cleanUp() throws Exception {
    parserState.close();
    executor.shutdown();
  }

  @Test
  public void testResolveSingleTargets() throws QueryException, InterruptedException {
    ImmutableSet<QueryTarget> targets;
    ImmutableSet<QueryTarget> expectedTargets;

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example:six", executor);
    expectedTargets = ImmutableSortedSet.of(createQueryBuildTarget("//example", "six"));
    assertThat(targets, is(equalTo(expectedTargets)));

    targets = buckQueryEnvironment.getTargetsMatchingPattern("//example/app:seven", executor);
    expectedTargets = ImmutableSortedSet.of(createQueryBuildTarget("//example/app", "seven"));
    assertThat(targets, is(equalTo(expectedTargets)));
  }

  @Test
  public void testResolveTargetPattern() throws QueryException, InterruptedException {
    ImmutableSet<QueryTarget> expectedTargets =
        ImmutableSortedSet.of(
            createQueryBuildTarget("//example", "one"),
            createQueryBuildTarget("//example", "two"),
            createQueryBuildTarget("//example", "three"),
            createQueryBuildTarget("//example", "four"),
            createQueryBuildTarget("//example", "five"),
            createQueryBuildTarget("//example", "six"),
            createQueryBuildTarget("//example", "application-test-lib"),
            createQueryBuildTarget("//example", "one-tests"),
            createQueryBuildTarget("//example", "four-tests"),
            createQueryBuildTarget("//example", "four-application-tests"),
            createQueryBuildTarget("//example", "six-tests"));
    assertThat(
        buckQueryEnvironment.getTargetsMatchingPattern("//example:", executor),
        is(equalTo(expectedTargets)));
  }
}
