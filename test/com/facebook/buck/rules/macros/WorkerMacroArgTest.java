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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.shell.WorkerToolBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerMacroArgTest {
  @Test
  public void testWorkerMacroArgConstruction() throws MacroException, NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(resolver);

    String startupArgs = "startupargs";
    Integer maxWorkers = 5;
    WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
        .setExe(shBinaryRule.getBuildTarget())
        .setArgs(startupArgs)
        .setMaxWorkers(maxWorkers)
        .build(resolver);

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String jobArgs = "jobargs";
    String unexpanded = "$(worker //:worker_rule) " + jobArgs;
    WorkerMacroArg arg =
        WorkerMacroArg.fromMacroArg(
            new MacroArg(macroHandler, target, cellNames, resolver, unexpanded),
            macroHandler,
            target,
            cellNames,
            resolver,
            unexpanded);
    assertThat(arg.getJobArgs(pathResolver), Matchers.equalTo(jobArgs));
    assertThat(
        arg.getStartupCommand().subList(1, 2),
        Matchers.equalTo(ImmutableList.<String>builder().add(startupArgs).build()));
    assertThat(arg.getMaxWorkers(), Matchers.equalTo(maxWorkers));
  }

  @Test
  public void testWorkerMacroArgWithNoMacros() throws MacroException, NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    String unexpanded = "no macros here";
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, resolver, unexpanded),
          macroHandler,
          target,
          cellNames,
          resolver,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("Unable to extract any build targets"));
    }
  }

  @Test
  public void testWorkerMacroArgWithBadReference()
      throws MacroException, NoSuchBuildTargetException {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    BuildRule nonWorkerBuildRule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//:not_worker_rule"));
    resolver.addToIndex(nonWorkerBuildRule);

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    String unexpanded = "$(worker //:not_worker_rule)";
    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, resolver, unexpanded),
          macroHandler,
          target,
          cellNames,
          resolver,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("does not correspond to a worker_tool"));
    }
  }

  @Test
  public void testWorkerMacroArgWithMacroInWrongLocation() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    String unexpanded = "mkdir && $(worker :worker)";

    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, resolver, unexpanded),
          macroHandler,
          target,
          cellNames,
          resolver,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("must be at the beginning"));
    }
  }
}
