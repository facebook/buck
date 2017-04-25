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

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.easymock.EasyMock;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class QueryCommandTest {

  private QueryCommand queryCommand;
  private CommandRunnerParams params;
  private ListeningExecutorService executor;

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
        new ProjectFilesystem(workspace.getDestPath().toRealPath().normalize());
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).build();
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    queryCommand = new QueryCommand();
    queryCommand.outputAttributes = Suppliers.ofInstance(ImmutableSet.<String>of());
    params =
        CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
            console,
            cell,
            androidDirectoryResolver,
            artifactCache,
            eventBus,
            FakeBuckConfig.builder().build(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv()),
            new FakeJavaPackageFinder(),
            Optional.empty());
    executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void testRunMultiQueryWithSet() throws Exception {
    queryCommand.setArguments(ImmutableList.of("deps(%Ss)", "//foo:bar", "//foo:baz"));
    EasyMock.expect(env.evaluateQuery("deps(set('//foo:bar' '//foo:baz'))", executor))
        .andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    queryCommand.formatAndRunQuery(params, env, executor);
    EasyMock.verify(env);
  }

  @Test
  public void testRunMultiQuery() throws Exception {
    queryCommand.setArguments(ImmutableList.of("deps(%s)", "//foo:bar", "//foo:baz"));
    EasyMock.expect(env.getFunctions())
        .andReturn(BuckQueryEnvironment.DEFAULT_QUERY_FUNCTIONS)
        .anyTimes();
    env.preloadTargetPatterns(ImmutableSet.of("//foo:bar", "//foo:baz"), executor);
    EasyMock.expect(env.evaluateQuery("deps(//foo:bar)", executor)).andReturn(ImmutableSet.of());
    EasyMock.expect(env.evaluateQuery("deps(//foo:baz)", executor)).andReturn(ImmutableSet.of());
    EasyMock.replay(env);
    queryCommand.formatAndRunQuery(params, env, executor);
    EasyMock.verify(env);
  }
}
