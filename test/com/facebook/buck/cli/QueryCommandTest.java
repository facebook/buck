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
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Optional;
import org.easymock.EasyMock;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class QueryCommandTest {

  private QueryCommand queryCommand;
  private CommandRunnerParams params;

  @Mock private BuckQueryEnvironment env;

  @Rule public EasyMockRule rule = new EasyMockRule(this);

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException, InterruptedException {
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
    queryCommand.outputAttributes = Suppliers.ofInstance(ImmutableSet.of());
    params =
        CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            console,
            cell,
            artifactCache,
            eventBus,
            FakeBuckConfig.builder().build(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()),
            new FakeJavaPackageFinder(),
            Optional.empty());
  }

  @Test
  public void testRunMultiQueryWithSet() throws Exception {
    queryCommand.setArguments(ImmutableList.of("deps(%Ss)", "//foo:bar", "//foo:baz"));
    EasyMock.expect(env.evaluateQuery("deps(set('//foo:bar' '//foo:baz'))"))
        .andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    queryCommand.formatAndRunQuery(params, env);
    EasyMock.verify(env);
  }

  @Test
  public void testRunMultiQueryWithSingleSetUsedMultipleTimes() throws Exception {
    queryCommand.setArguments(
        ImmutableList.of("deps(%Ss) union testsof(%Ss)", "//foo:libfoo", "//foo:libfootoo"));
    EasyMock.expect(
            env.evaluateQuery(
                "deps(set('//foo:libfoo' '//foo:libfootoo')) union testsof(set('//foo:libfoo' '//foo:libfootoo'))"))
        .andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    queryCommand.formatAndRunQuery(params, env);
    EasyMock.verify(env);
  }

  @Test
  public void testRunMultiQueryWithMultipleDifferentSets() throws Exception {
    queryCommand.setArguments(
        ImmutableList.of(
            "deps(%Ss) union testsof(%Ss)",
            "//foo:libfoo", "//foo:libfootoo", "--", "//bar:libbar", "//bar:libbaz"));
    EasyMock.expect(
            env.evaluateQuery(
                "deps(set('//foo:libfoo' '//foo:libfootoo')) union testsof(set('//bar:libbar' '//bar:libbaz'))"))
        .andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    queryCommand.formatAndRunQuery(params, env);
    EasyMock.verify(env);
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
    QueryEnvironment.TargetEvaluator evaluator =
        EasyMock.createNiceMock(QueryEnvironment.TargetEvaluator.class);
    EasyMock.expect(evaluator.getType())
        .andReturn(QueryEnvironment.TargetEvaluator.Type.LAZY)
        .times(2);
    EasyMock.expect(env.getFunctions())
        .andReturn(BuckQueryEnvironment.DEFAULT_QUERY_FUNCTIONS)
        .anyTimes();
    EasyMock.expect(env.getTargetEvaluator()).andReturn(evaluator).times(2);
    env.preloadTargetPatterns(ImmutableSet.of("//foo:bar", "//foo:baz"));
    EasyMock.expect(env.evaluateQuery("deps(//foo:bar)")).andReturn(ImmutableSet.of());
    EasyMock.expect(env.evaluateQuery("deps(//foo:baz)")).andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    EasyMock.replay(evaluator);
    queryCommand.formatAndRunQuery(params, env);
    EasyMock.verify(env);
    EasyMock.verify(evaluator);
  }
}
