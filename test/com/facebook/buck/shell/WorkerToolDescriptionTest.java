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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
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

  private static WorkerTool createWorkerTool(int maxWorkers) throws NoSuchBuildTargetException {
    TargetGraph targetGraph = TargetGraph.EMPTY;
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    WorkerToolDescriptionArg args =
        WorkerToolDescriptionArg.builder()
            .setName("target")
            .setExe(shBinaryRule.getBuildTarget())
            .setMaxWorkers(maxWorkers)
            .build();

    WorkerToolDescription workerToolDescription =
        new WorkerToolDescription(FakeBuckConfig.builder().build());
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//arbitrary:target").build();
    return (WorkerTool)
        workerToolDescription.createBuildRule(
            targetGraph,
            params,
            resolver,
            TestCellBuilder.createCellRoots(params.getProjectFilesystem()),
            args);
  }
}
