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
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerToolTest {

  @Test
  public void testCreateWorkerTool() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    BuildRule workerRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(StringWithMacrosUtils.format("arg1"), StringWithMacrosUtils.format("arg2"))
            .build(resolver);

    assertThat(
        "getArgs should return the args string supplied in the definition.",
        ImmutableList.of("arg1", "arg2"),
        Matchers.is(
            ((WorkerTool) workerRule).getTool().getCommandPrefix(pathResolver).subList(1, 3)));
  }

  @Test
  public void testCreateWorkerToolWithBadExeValue() throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    BuildRule nonBinaryBuildRule = new FakeBuildRule(BuildTargetFactory.newInstance("//:fake"));
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
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(
                StringWithMacrosUtils.format(
                    "--input %s", LocationMacro.of(exportFileRule.getBuildTarget())));
    DefaultWorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        workerToolBuilder.build().getExtraDeps(),
        Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerTool.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerTool.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        Joiner.on(' ').join(workerTool.getTool().getCommandPrefix(pathResolver)),
        Matchers.containsString(
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testEnvWithLocationMacroAffectDependenciesAndExpands() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setEnv(
                ImmutableMap.of(
                    "ENV_VAR_NAME",
                    StringWithMacrosUtils.format(
                        "%s", LocationMacro.of(exportFileRule.getBuildTarget()))));
    DefaultWorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        workerToolBuilder.build().getExtraDeps(),
        Matchers.hasItem(exportFileRule.getBuildTarget()));
    assertThat(workerTool.getBuildDeps(), Matchers.hasItems(shBinaryRule, exportFileRule));
    assertThat(
        workerTool.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.hasItems(shBinaryRule.getBuildTarget(), exportFileRule.getBuildTarget()));
    assertThat(
        workerTool.getTool().getEnvironment(pathResolver),
        Matchers.hasEntry(
            "ENV_VAR_NAME",
            pathResolver.getAbsolutePath(exportFileRule.getSourcePathToOutput()).toString()));
  }

  @Test
  public void testUnderlyingToolIncludesDependenciesAsInputs() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    BuildRule exportFileRule =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//:file"))
            .setSrc(FakeSourcePath.of("file.txt"))
            .build(resolver);

    WorkerToolBuilder workerToolBuilder =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .setArgs(
                StringWithMacrosUtils.format("--input"),
                StringWithMacrosUtils.format(
                    "%s", LocationMacro.of(exportFileRule.getBuildTarget())));
    WorkerTool workerTool = workerToolBuilder.build(resolver);

    assertThat(
        BuildableSupport.deriveInputs(workerTool.getTool())
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(exportFileRule.getSourcePathToOutput()));
  }
}
