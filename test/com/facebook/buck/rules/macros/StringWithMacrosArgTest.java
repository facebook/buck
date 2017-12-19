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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class StringWithMacrosArgTest {

  private static final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS = ImmutableList.of(new LocationMacroExpander());
  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:target");
  private static final CellPathResolver CELL_PATH_RESOLVER =
      TestCellPathResolver.get(new FakeProjectFilesystem());

  private final BuildRuleResolver resolver =
      new SingleThreadedBuildRuleResolver(
          TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
  private BuildRule rule1;
  private BuildRule rule2;
  private StringWithMacrosArg noMacrosArg;
  private StringWithMacrosArg oneMacroArg;
  private StringWithMacrosArg multipleMacrosArg;

  @Before
  public void setUp() throws Exception {
    rule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule1"))
            .setOut("out")
            .build(resolver);
    rule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule2"))
            .setOut("out")
            .build(resolver);
    noMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test"),
            MACRO_EXPANDERS,
            Optional.empty(),
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    oneMacroArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format("--test=%s", LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            Optional.empty(),
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
    multipleMacrosArg =
        StringWithMacrosArg.of(
            StringWithMacrosUtils.format(
                "--test=%s --test2=%s --test3=%s",
                LocationMacro.of(rule1.getBuildTarget()),
                LocationMacro.of(rule2.getBuildTarget()),
                LocationMacro.of(rule1.getBuildTarget())),
            MACRO_EXPANDERS,
            Optional.empty(),
            TARGET,
            CELL_PATH_RESOLVER,
            resolver);
  }

  @Test
  public void getDeps() throws NoSuchBuildTargetException {
    SourcePathRuleFinder pathFinder = new SourcePathRuleFinder(resolver);

    // Test no embedded macros.
    assertThat(BuildableSupport.getDepsCollection(noMacrosArg, pathFinder), Matchers.empty());

    // Test one embedded macros.
    assertThat(
        BuildableSupport.getDepsCollection(oneMacroArg, pathFinder), Matchers.contains(rule1));

    // Test multiple embedded macros.
    assertThat(
        BuildableSupport.getDepsCollection(multipleMacrosArg, pathFinder),
        Matchers.contains(rule1, rule2, rule1));
  }

  @Test
  public void getInputs() throws NoSuchBuildTargetException {
    // Test no embedded macros.
    assertThat(
        BuildableSupport.deriveInputs(noMacrosArg).collect(ImmutableList.toImmutableList()),
        Matchers.empty());

    // Test one embedded macros.
    assertThat(
        BuildableSupport.deriveInputs(oneMacroArg).collect(ImmutableList.toImmutableList()),
        Matchers.contains(rule1.getSourcePathToOutput()));

    // Test multiple embedded macros.
    assertThat(
        BuildableSupport.deriveInputs(multipleMacrosArg).collect(ImmutableList.toImmutableList()),
        Matchers.contains(
            rule1.getSourcePathToOutput(),
            rule2.getSourcePathToOutput(),
            rule1.getSourcePathToOutput()));
  }

  @Test
  public void appendToCommandLine() throws NoSuchBuildTargetException {
    SourcePathRuleFinder pathFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(pathFinder);

    // Test no embedded macros.
    assertThat(
        Arg.stringifyList(noMacrosArg, pathResolver), Matchers.equalTo(ImmutableList.of("--test")));

    // Test one embedded macros.
    assertThat(
        Arg.stringifyList(oneMacroArg, pathResolver),
        Matchers.equalTo(
            ImmutableList.of(
                String.format(
                    "--test=%s", pathResolver.getAbsolutePath(rule1.getSourcePathToOutput())))));

    // Test multiple embedded macros.
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

  @Test
  public void expand() throws NoSuchBuildTargetException {
    SourcePathRuleFinder pathFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(pathFinder);

    // Test no embedded macros.
    assertEquals("--test", noMacrosArg.expand());

    // Test one embedded macro.
    assertEquals(
        String.format("--test=%s", pathResolver.getAbsolutePath(rule1.getSourcePathToOutput())),
        oneMacroArg.expand());

    // Test multiple embedded macros.
    assertEquals(
        String.format(
            "--test=%s --test2=%s --test3=%s",
            pathResolver.getAbsolutePath(rule1.getSourcePathToOutput()),
            pathResolver.getAbsolutePath(rule2.getSourcePathToOutput()),
            pathResolver.getAbsolutePath(rule1.getSourcePathToOutput())),
        multipleMacrosArg.expand());
  }

  @Test
  public void expandWithTransform() {
    String transformed = "apples";
    assertEquals(String.format("--test=%s", transformed), oneMacroArg.expand(s -> transformed));
  }
}
