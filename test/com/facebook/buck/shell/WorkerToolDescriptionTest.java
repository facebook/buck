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

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleCreationContextFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.util.function.BiFunction;
import org.junit.Test;

public class WorkerToolDescriptionTest {
  @Test
  public void testGetMaxWorkersWhenSet() throws NoSuchBuildTargetException {
    int maxWorkers = 14;
    WorkerTool workerTool = createWorkerTool(maxWorkers);
    assertThat(workerTool.getMaxWorkers(), equalTo(maxWorkers));
  }

  @Test
  public void testGetMaxWorkersWhenSetToZero() throws NoSuchBuildTargetException {
    WorkerTool workerTool = createWorkerTool(0);
    assertThat(workerTool.getMaxWorkers(), equalTo(Integer.MAX_VALUE));
  }

  @Test
  public void testGetMaxWorkersWhenSetToNegativeInt() throws NoSuchBuildTargetException {
    WorkerTool workerTool = createWorkerTool(-2);
    assertThat(workerTool.getMaxWorkers(), equalTo(Integer.MAX_VALUE));
  }

  @Test
  public void testHandlesExeWithoutOutput() throws NoSuchBuildTargetException {
    createWorkerTool(1, WorkerToolDescriptionTest::wrapExeInCommandAlias);
  }

  private static WorkerTool createWorkerTool(int maxWorkers) throws NoSuchBuildTargetException {
    return createWorkerTool(maxWorkers, (resolver, shBinary) -> shBinary.getBuildTarget());
  }

  private static BuildTarget wrapExeInCommandAlias(BuildRuleResolver resolver, BuildRule shBinary) {
    return new CommandAliasBuilder(BuildTargetFactory.newInstance("//:no_output"))
        .setExe(shBinary.getBuildTarget())
        .build(resolver)
        .getBuildTarget();
  }

  private static WorkerTool createWorkerTool(
      int maxWorkers, BiFunction<BuildRuleResolver, BuildRule, BuildTarget> getExe)
      throws NoSuchBuildTargetException {
    TargetGraph targetGraph = TargetGraph.EMPTY;
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    BuildTarget exe = getExe.apply(resolver, shBinaryRule);
    WorkerToolDescriptionArg args =
        WorkerToolDescriptionArg.builder()
            .setName("target")
            .setExe(exe)
            .setMaxWorkers(maxWorkers)
            .build();

    WorkerToolDescription workerToolDescription =
        new WorkerToolDescription(FakeBuckConfig.builder().build());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//arbitrary:target");
    BuildRuleParams params =
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(),
            () -> ImmutableSortedSet.of(resolver.getRule(exe)),
            ImmutableSortedSet.of());
    return (WorkerTool)
        workerToolDescription.createBuildRule(
            TestBuildRuleCreationContextFactory.create(targetGraph, resolver, projectFilesystem),
            buildTarget,
            params,
            args);
  }
}
