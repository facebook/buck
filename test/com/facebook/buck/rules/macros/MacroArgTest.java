/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class MacroArgTest {

  @Test
  public void stringify() {
    MacroHandler macroHandler =
        new MacroHandler(
            ImmutableMap.of("macro", new StringExpander<>(Macro.class, StringArg.of("expanded"))));
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroArg arg =
        new MacroArg(
            macroHandler,
            BuildTargetFactory.newInstance("//:rule"),
            TestCellBuilder.createCellRoots(filesystem),
            resolver,
            "$(macro)");
    assertThat(Arg.stringifyList(arg, pathResolver), Matchers.contains("expanded"));
  }

  @Test
  public void getDeps() {
    MacroHandler macroHandler =
        new MacroHandler(ImmutableMap.of("loc", new LocationMacroExpander()));
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    Genrule rule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(resolver);
    MacroArg arg =
        new MacroArg(
            macroHandler,
            rule.getBuildTarget(),
            TestCellBuilder.createCellRoots(filesystem),
            resolver,
            "$(loc //:rule)");
    assertThat(BuildableSupport.getDepsCollection(arg, ruleFinder), Matchers.contains(rule));
  }
}
