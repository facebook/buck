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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.shell.ShBinaryBuilder;
import com.facebook.buck.shell.WorkerToolBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerMacroArgTest {
  @Test
  public void testWorkerMacroArgConstruction() throws MacroException, NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    String startupArgs = "startupargs";
    Integer maxWorkers = 5;
    WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
        .setExe(shBinaryRule.getBuildTarget())
        .setArgs(StringWithMacrosUtils.format(startupArgs))
        .setMaxWorkers(maxWorkers)
        .build(graphBuilder);

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String jobArgs = "jobargs";
    String unexpanded = "$(worker //:worker_rule) " + jobArgs;
    WorkerMacroArg arg =
        WorkerMacroArg.fromMacroArg(
            new MacroArg(macroHandler, target, cellNames, graphBuilder, unexpanded),
            macroHandler,
            target,
            cellNames,
            graphBuilder,
            unexpanded);
    assertThat(arg.getJobArgs(pathResolver), Matchers.equalTo(jobArgs));
    assertThat(
        arg.getStartupCommand().subList(1, 2),
        Matchers.equalTo(ImmutableList.<String>builder().add(startupArgs).build()));
    assertThat(arg.getMaxWorkers(), Matchers.equalTo(maxWorkers));
  }

  @Test
  public void testWorkerMacroArgWithNoMacros() throws MacroException, NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    String unexpanded = "no macros here";
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, graphBuilder, unexpanded),
          macroHandler,
          target,
          cellNames,
          graphBuilder,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("Unable to extract any build targets"));
    }
  }

  @Test
  public void testWorkerMacroArgWithBadReference()
      throws MacroException, NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule nonWorkerBuildRule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//:not_worker_rule"));
    graphBuilder.addToIndex(nonWorkerBuildRule);

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    String unexpanded = "$(worker //:not_worker_rule)";
    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, graphBuilder, unexpanded),
          macroHandler,
          target,
          cellNames,
          graphBuilder,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("does not correspond to a worker_tool"));
    }
  }

  @Test
  public void testWorkerMacroArgWithMacroInWrongLocation() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CellPathResolver cellNames = TestCellBuilder.createCellRoots(filesystem);
    String unexpanded = "mkdir && $(worker :worker)";

    try {
      WorkerMacroArg.fromMacroArg(
          new MacroArg(macroHandler, target, cellNames, graphBuilder, unexpanded),
          macroHandler,
          target,
          cellNames,
          graphBuilder,
          unexpanded);
    } catch (MacroException e) {
      assertThat(e.getMessage(), Matchers.containsString("must be at the beginning"));
    }
  }
}
