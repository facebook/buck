/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;

import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerToolTest {

  @Test
  public void testCreateWorkerTool() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule shBinaryRule = new ShBinaryBuilder(
        BuildTargetFactory.newInstance("//:my_exe"))
        .setMain(new FakeSourcePath("bin/exe"))
        .build(resolver);

    BuildRule workerRule = WorkerToolBuilder
        .newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
        .setExe(shBinaryRule.getBuildTarget())
        .setArgs("arg1 arg2")
        .build(resolver);

    assertThat(
        "getBinaryBuildRule should return the build rule supplied in the definition.",
        shBinaryRule,
        Matchers.equalToObject(((WorkerTool) workerRule).getBinaryBuildRule()));

    assertThat(
        "getArgs should return the args string supplied in the definition.",
        "arg1 arg2",
        Matchers.is(((WorkerTool) workerRule).getArgs()));
  }

  @Test
  public void testCreateWorkerToolWithBadExeValue() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule nonBinaryBuildRule = new FakeBuildRule(
        BuildTargetFactory.newInstance("//:fake"),
        new SourcePathResolver(resolver));
    resolver.addToIndex(nonBinaryBuildRule);

    BuildTarget workerTarget = BuildTargetFactory.newInstance("//:worker_rule");
    try {
      WorkerToolBuilder
          .newWorkerToolBuilder(workerTarget)
          .setExe(nonBinaryBuildRule.getBuildTarget())
          .build(resolver);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.containsString("needs to correspond to a binary rule"));
    }
  }
}
