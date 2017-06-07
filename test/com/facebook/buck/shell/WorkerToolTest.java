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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerToolTest {

  @Test
  public void testCreateWorkerTool() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    BuildRule workerRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs("arg1", "arg2")
            .build(resolver);

    assertThat(
        "getArgs should return the args string supplied in the definition.",
        "arg1 arg2",
        Matchers.is(((WorkerTool) workerRule).getArgs(pathResolver)));
  }

  @Test
  public void testCreateWorkerToolWithBadExeValue() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule nonBinaryBuildRule =
        new FakeBuildRule(
            BuildTargetFactory.newInstance("//:fake"),
            new SourcePathResolver(new SourcePathRuleFinder(resolver)));
    resolver.addToIndex(nonBinaryBuildRule);

    BuildTarget workerTarget = BuildTargetFactory.newInstance("//:worker_rule");
    try {
      WorkerToolBuilder.newWorkerToolBuilder(workerTarget)
          .setExe(nonBinaryBuildRule.getBuildTarget())
          .build(resolver);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.containsString("needs to correspond to a binary rule"));
    }
  }

  @Test
  public void testArgsWithLocationMacroAffectDependenciesAndExpands() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(new FakeSourcePath("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs("--input $(location //:file)");
    DefaultWorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        workerToolBuilder.findImplicitDeps(), Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerTool.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerTool.getRuntimeDeps().collect(MoreCollectors.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        workerTool.getArgs(pathResolver),
        Matchers.containsString(
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testEnvWithLocationMacroAffectDependenciesAndExpands() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(new FakeSourcePath("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setEnv(ImmutableMap.of("ENV_VAR_NAME", "$(location //:file)"));
    DefaultWorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        workerToolBuilder.findImplicitDeps(), Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerTool.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerTool.getRuntimeDeps().collect(MoreCollectors.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        workerTool.getTool().getEnvironment(pathResolver),
        Matchers.hasEntry(
            "ENV_VAR_NAME",
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testUnderlyingToolIncludesDependenciesAsInputs() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(new FakeSourcePath("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs("--input", "$(location //:file)");
    WorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        workerTool.getTool().getInputs(), Matchers.hasItem(exportFileRule.getSourcePathToOutput()));
  }
}
