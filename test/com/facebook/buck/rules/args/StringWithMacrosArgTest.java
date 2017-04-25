/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeCellPathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class StringWithMacrosArgTest {

  private static final ImmutableList<AbstractMacroExpander<? extends Macro>> MACRO_EXPANDERS =
      ImmutableList.of(new LocationMacroExpander());
  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final CellPathResolver CELL_PATH_RESOLVER =
      new FakeCellPathResolver(new FakeProjectFilesystem());

  private final BuildRuleResolver resolver =
      new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

  @Test
  public void getDeps() throws NoSuchBuildTargetException {
    SourcePathRuleFinder pathFinder = new SourcePathRuleFinder(resolver);

    BuildRule rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out")
            .build(resolver);
    BuildRule rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out")
            .build(resolver);

    // Test no embedded macros.
    StringWithMacrosArg noMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test"),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(noMacrosArg.getDeps(pathFinder), Matchers.empty());

    // Test one embedded macros.
    StringWithMacrosArg oneMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test=%s", LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(oneMacrosArg.getDeps(pathFinder), Matchers.contains(rule1));

    // Test multiple embedded macros.
    StringWithMacrosArg multipleMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format(
                "--test=%s --test2=%s --test3=%s",
                LocationMacro.of(rule1.getBuildTarget()),
                LocationMacro.of(rule2.getBuildTarget()),
                LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(multipleMacrosArg.getDeps(pathFinder), Matchers.contains(rule1, rule2, rule1));
  }

  @Test
  public void getInputs() throws NoSuchBuildTargetException {
    BuildRule rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out")
            .build(resolver);
    BuildRule rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out")
            .build(resolver);

    // Test no embedded macros.
    StringWithMacrosArg noMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test"),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(noMacrosArg.getInputs(), Matchers.empty());

    // Test one embedded macros.
    StringWithMacrosArg oneMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test=%s", LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(oneMacrosArg.getInputs(), Matchers.contains(rule1.getSourcePathToOutput()));

    // Test multiple embedded macros.
    StringWithMacrosArg multipleMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format(
                "--test=%s --test2=%s --test3=%s",
                LocationMacro.of(rule1.getBuildTarget()),
                LocationMacro.of(rule2.getBuildTarget()),
                LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(
        multipleMacrosArg.getInputs(),
        Matchers.contains(
            rule1.getSourcePathToOutput(),
            rule2.getSourcePathToOutput(),
            rule1.getSourcePathToOutput()));
  }

  @Test
  public void appendToCommandLine() throws NoSuchBuildTargetException {
    SourcePathRuleFinder pathFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(pathFinder);

    BuildRule rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out")
            .build(resolver);
    BuildRule rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out")
            .build(resolver);

    // Test no embedded macros.
    StringWithMacrosArg noMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test"),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(
        Arg.stringifyList(noMacrosArg, pathResolver), Matchers.equalTo(ImmutableList.of("--test")));

    // Test one embedded macros.
    StringWithMacrosArg oneMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test=%s", LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(
        Arg.stringifyList(oneMacrosArg, pathResolver),
        Matchers.equalTo(
            ImmutableList.of(
                String.format(
                    "--test=%s", pathResolver.getAbsolutePath(rule1.getSourcePathToOutput())))));

    // Test multiple embedded macros.
    StringWithMacrosArg multipleMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format(
                "--test=%s --test2=%s --test3=%s",
                LocationMacro.of(rule1.getBuildTarget()),
                LocationMacro.of(rule2.getBuildTarget()),
                LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    assertThat(
        Arg.stringifyList(multipleMacrosArg, pathResolver),
        Matchers.equalTo(
            ImmutableList.of(
                String.format(
                    "--test=%s --test2=%s --test3=%s",
                    pathResolver.getAbsolutePath(rule1.getSourcePathToOutput()),
                    pathResolver.getAbsolutePath(rule2.getSourcePathToOutput()),
                    pathResolver.getAbsolutePath(rule1.getSourcePathToOutput())))));
  }
}
