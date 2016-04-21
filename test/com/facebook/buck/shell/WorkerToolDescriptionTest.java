/*
 * Copyright 2016-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;

import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerToolDescriptionTest {
  @Test
  public void testArgsWithLocationMacroAffectDependenciesAndExpand() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildRule shBinaryRule = new ShBinaryBuilder(
        BuildTargetFactory.newInstance("//:my_exe"))
        .setMain(new FakeSourcePath("bin/exe"))
        .build(resolver);

    BuildRule exportFileRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
          .setSrc(new FakeSourcePath("file.txt"))
          .build(resolver);

    WorkerToolBuilder workerToolBuilder = WorkerToolBuilder
        .newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
        .setExe(shBinaryRule.getBuildTarget())
        .setArgs("--input $(location //:file)");
    WorkerTool workerTool = (WorkerTool) workerToolBuilder.build(resolver);

    assertThat(
        workerToolBuilder.findImplicitDeps(),
        Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerTool.getDeps(), Matchers.hasItem(exportFileRule));
    assertThat(
        workerTool.getArgs(), Matchers.containsString(
            pathResolver.getAbsolutePath(
                new BuildTargetSourcePath(exportFileRule.getBuildTarget())).toString()));
  }
}
