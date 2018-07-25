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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.WorkerToolDescriptionArg.Builder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.BeforeClass;
import org.junit.Test;

public class WorkerToolDescriptionTest {

  private static final BiFunction<ActionGraphBuilder, BuildRule, BuildTarget>
      getShBinaryBuildTarget = (resolver, shBinary) -> shBinary.getBuildTarget();
  private static final int NUM_THREADS = 16;

  private static BuckConfig BUCK_CONFIG;

  @BeforeClass
  public static void setUp() {
    BUCK_CONFIG =
        FakeBuckConfig.builder()
            .setSections("[build]", String.format("threads = %d", NUM_THREADS))
            .build();
  }

  @Test
  public void testGetMaxWorkersWhenSet() {
    int maxWorkers = 14;
    WorkerTool workerTool = createWorkerToolWithAbsoluteNumber(maxWorkers);
    assertThat(workerTool.getMaxWorkers(), equalTo(maxWorkers));
  }

  @Test
  public void testDefaultGetMaxWorkers() {
    WorkerTool workerTool = createWorkerToolWithDefaultSettings();
    assertThat(workerTool.getMaxWorkers(), equalTo(1));
  }

  @Test
  public void testGetMaxWorkersWhenSetToZero() {
    WorkerTool workerTool = createWorkerToolWithAbsoluteNumber(0);
    assertThat(workerTool.getMaxWorkers(), equalTo(NUM_THREADS));
  }

  @Test
  public void testGetMaxWorkersWhenSetToNegativeInt() {
    WorkerTool workerTool = createWorkerToolWithAbsoluteNumber(-2);
    assertThat(workerTool.getMaxWorkers(), equalTo(NUM_THREADS));
  }

  @Test
  public void testHandlesExeWithoutOutput() {
    createWorkerToolWithAbsoluteNumber(1, WorkerToolDescriptionTest::wrapExeInCommandAlias);
  }

  @Test
  public void testGetMaxWorkersPerThreadPercentWhenSet() {
    int maxWorkersPerThreadPercent = 75;
    WorkerTool workerTool = createWorkerToolWithPercent(maxWorkersPerThreadPercent);
    assertThat(
        workerTool.getMaxWorkers(),
        equalTo((int) (NUM_THREADS * maxWorkersPerThreadPercent / 100.0)));
  }

  @Test
  public void testGetMaxWorkersPerThreadPercentSupportsOne() {
    WorkerTool workerTool = createWorkerToolWithPercent(100);
    assertThat(workerTool.getMaxWorkers(), equalTo(NUM_THREADS));
  }

  @Test
  public void testGetMaxWorkersPerThreadPercentCreatesAtLeastOneWorker() {
    int maxWorkersPerThreadPercent = 100 / (NUM_THREADS * 4);
    WorkerTool workerTool = createWorkerToolWithPercent(maxWorkersPerThreadPercent);
    assertThat(workerTool.getMaxWorkers(), equalTo(1));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMaxWorkersPerThreadMustNotBeZero() {
    createWorkerToolWithPercent(0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMaxWorkersPerThreadMustNotBeNegative() {
    createWorkerToolWithPercent(-12);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMaxWorkersPerThreadMustNotBeGreaterThanOne() {
    createWorkerToolWithPercent(105);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testAbsoluteAndPercentCannotBeUsedTogether() {
    createWorkerTool(builder -> builder.setMaxWorkers(123).setMaxWorkersPerThreadPercent(45));
  }

  private static WorkerTool createWorkerToolWithPercent(int maxWorkersPerThreadPercent) {
    return createWorkerTool(
        builder -> builder.setMaxWorkersPerThreadPercent(maxWorkersPerThreadPercent),
        getShBinaryBuildTarget);
  }

  private static WorkerTool createWorkerToolWithAbsoluteNumber(int maxWorkers) {
    return createWorkerToolWithAbsoluteNumber(maxWorkers, getShBinaryBuildTarget);
  }

  private static WorkerTool createWorkerToolWithAbsoluteNumber(
      int maxWorkers, BiFunction<ActionGraphBuilder, BuildRule, BuildTarget> getExe) {
    return createWorkerTool(builder -> builder.setMaxWorkers(maxWorkers), getExe);
  }

  private static WorkerTool createWorkerToolWithDefaultSettings() {
    return createWorkerTool(builder -> {}, getShBinaryBuildTarget);
  }

  private static BuildTarget wrapExeInCommandAlias(
      ActionGraphBuilder graphBuilder, BuildRule shBinary) {
    return new CommandAliasBuilder(BuildTargetFactory.newInstance("//:no_output"))
        .setExe(shBinary.getBuildTarget())
        .build(graphBuilder)
        .getBuildTarget();
  }

  private static WorkerTool createWorkerTool(Consumer<WorkerToolDescriptionArg.Builder> setArgs) {
    return createWorkerTool(setArgs, getShBinaryBuildTarget);
  }

  private static WorkerTool createWorkerTool(
      Consumer<WorkerToolDescriptionArg.Builder> setArgs,
      BiFunction<ActionGraphBuilder, BuildRule, BuildTarget> getExe) {
    TargetGraph targetGraph = TargetGraph.EMPTY;
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildTarget exe = getExe.apply(graphBuilder, shBinaryRule);
    Builder argsBuilder = WorkerToolDescriptionArg.builder().setName("target").setExe(exe);
    setArgs.accept(argsBuilder);

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
            argsBuilder.build());
  }
}
