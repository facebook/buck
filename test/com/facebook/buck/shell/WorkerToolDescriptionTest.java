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

package com.facebook.buck.shell;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleCreationContextFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.util.function.BiFunction;
import org.junit.BeforeClass;
import org.junit.Test;

public class WorkerToolDescriptionTest {
  private static BuckConfig BUCK_CONFIG;

  @BeforeClass
  public static void setUp() {
    BUCK_CONFIG = FakeBuckConfig.builder().build();
  }

  @Test
  public void testGetMaxWorkersWhenSet() throws NoSuchBuildTargetException {
    int maxWorkers = 14;
    WorkerTool workerTool = createWorkerTool(maxWorkers);
    assertThat(workerTool.getMaxWorkers(), equalTo(maxWorkers));
  }

  @Test
  public void testGetMaxWorkersWhenSetToZero() throws NoSuchBuildTargetException {
    WorkerTool workerTool = createWorkerTool(0);
    assertThat(workerTool.getMaxWorkers(), equalTo(BUCK_CONFIG.getNumThreads()));
  }

  @Test
  public void testGetMaxWorkersWhenSetToNegativeInt() throws NoSuchBuildTargetException {
    WorkerTool workerTool = createWorkerTool(-2);
    assertThat(workerTool.getMaxWorkers(), equalTo(BUCK_CONFIG.getNumThreads()));
  }

  @Test
  public void testHandlesExeWithoutOutput() throws NoSuchBuildTargetException {
    createWorkerTool(1, WorkerToolDescriptionTest::wrapExeInCommandAlias);
  }

  private static WorkerTool createWorkerTool(int maxWorkers) throws NoSuchBuildTargetException {
    return createWorkerTool(maxWorkers, (resolver, shBinary) -> shBinary.getBuildTarget());
  }

  private static BuildTarget wrapExeInCommandAlias(
      ActionGraphBuilder graphBuilder, BuildRule shBinary) {
    return new CommandAliasBuilder(BuildTargetFactory.newInstance("//:no_output"))
        .setExe(shBinary.getBuildTarget())
        .build(graphBuilder)
        .getBuildTarget();
  }

  private static WorkerTool createWorkerTool(
      int maxWorkers, BiFunction<ActionGraphBuilder, BuildRule, BuildTarget> getExe)
      throws NoSuchBuildTargetException {
    TargetGraph targetGraph = TargetGraph.EMPTY;
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildTarget exe = getExe.apply(graphBuilder, shBinaryRule);
    WorkerToolDescriptionArg args =
        WorkerToolDescriptionArg.builder()
            .setName("target")
            .setExe(exe)
            .setMaxWorkers(maxWorkers)
            .build();

    WorkerToolDescription workerToolDescription = new WorkerToolDescription(BUCK_CONFIG);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//arbitrary:target");
    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of,
            () -> ImmutableSortedSet.of(graphBuilder.getRule(exe)),
            ImmutableSortedSet.of());
    return (WorkerTool)
        workerToolDescription.createBuildRule(
            TestBuildRuleCreationContextFactory.create(
                targetGraph, graphBuilder, projectFilesystem),
            buildTarget,
            params,
            args);
  }
}
