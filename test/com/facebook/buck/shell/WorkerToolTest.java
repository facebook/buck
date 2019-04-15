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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerToolTest {

  @Test
  public void testCreateWorkerTool() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule workerRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(StringWithMacrosUtils.format("arg1"), StringWithMacrosUtils.format("arg2"))
            .build(graphBuilder);

    assertThat(
        "getArgs should return the args string supplied in the definition.",
        ImmutableList.of("arg1", "arg2"),
        Matchers.is(
            ((ProvidesWorkerTool) workerRule)
                .getWorkerTool()
                .getTool()
                .getCommandPrefix(pathResolver)
                .subList(1, 3)));
  }

  @Test
  public void testCreateWorkerToolWithBadExeValue() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule nonBinaryBuildRule = new FakeBuildRule(BuildTargetFactory.newInstance("//:fake"));
    graphBuilder.addToIndex(nonBinaryBuildRule);

    BuildTarget workerTarget = BuildTargetFactory.newInstance("//:worker_rule");
    try {
      WorkerToolBuilder.newWorkerToolBuilder(workerTarget)
          .setExe(nonBinaryBuildRule.getBuildTarget())
          .build(graphBuilder);
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          Matchers.containsString("needs to correspond to a binary rule"));
    }
  }

  @Test
  public void testArgsWithLocationMacroAffectDependenciesAndExpands() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(graphBuilder);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(
                StringWithMacrosUtils.format(
                    "--input %s", LocationMacro.of(exportFileRule.getBuildTarget())));
    DefaultWorkerToolRule workerToolRule = workerToolBuilder.build(graphBuilder);

    assertThat(
        workerToolBuilder.build().getExtraDeps(),
        Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerToolRule.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerToolRule.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        Joiner.on(' ')
            .join(workerToolRule.getWorkerTool().getTool().getCommandPrefix(pathResolver)),
        Matchers.containsString(
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testEnvWithLocationMacroAffectDependenciesAndExpands() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(graphBuilder);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setEnv(
                ImmutableMap.of(
                    "ENV_VAR_NAME",
                    StringWithMacrosUtils.format(
                        "%s", LocationMacro.of(exportFileRule.getBuildTarget()))));
    DefaultWorkerToolRule workerToolRule = workerToolBuilder.build(graphBuilder);

    assertThat(
        workerToolBuilder.build().getExtraDeps(),
        Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerToolRule.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerToolRule.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        workerToolRule.getWorkerTool().getTool().getEnvironment(pathResolver),
        Matchers.hasEntry(
            "ENV_VAR_NAME",
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testUnderlyingToolIncludesDependenciesAsInputs() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(graphBuilder);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(
                StringWithMacrosUtils.format("--input"),
                StringWithMacrosUtils.format(
                    "%s", LocationMacro.of(exportFileRule.getBuildTarget())));
    WorkerTool workerTool = workerToolBuilder.build(graphBuilder).getWorkerTool();

    assertThat(
        BuildableSupport.deriveInputs(workerTool.getTool())
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(exportFileRule.getSourcePathToOutput()));
  }
}
