/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class StringWithMacrosConverterTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:rule");
  private static final CellPathResolver CELL_ROOTS =
      TestCellPathResolver.get(new FakeProjectFilesystem());
  private static final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS = ImmutableList.of(new LocationMacroExpander());

  @Test
  public void noMacros() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, resolver, MACRO_EXPANDERS);
    assertThat(
        converter.convert(StringWithMacrosUtils.format("something")),
        Matchers.equalTo(CompositeArg.of(ImmutableList.of(StringArg.of("something")))));
  }

  @Test
  public void macro() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, resolver, MACRO_EXPANDERS);
    assertThat(
        converter.convert(
            StringWithMacrosUtils.format("%s", LocationMacro.of(genrule.getBuildTarget()))),
        Matchers.equalTo(
            CompositeArg.of(
                ImmutableList.of(
                    SourcePathArg.of(
                        Preconditions.checkNotNull(genrule.getSourcePathToOutput()))))));
  }

  @Test
  public void sanitization() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(TARGET)
            .setCellPathResolver(CELL_ROOTS)
            .setResolver(resolver)
            .setExpanders(MACRO_EXPANDERS)
            .setSanitizer(s -> "something else")
            .build();
    assertThat(
        converter.convert(StringWithMacrosUtils.format("something")),
        Matchers.equalTo(
            CompositeArg.of(
                ImmutableList.of(SanitizedArg.create(s -> "something else", "something")))));
  }

  @Test
  public void outputToFileMacro() {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(TARGET, CELL_ROOTS, resolver, MACRO_EXPANDERS);
    Arg result =
        converter.convert(
            StringWithMacrosUtils.format(
                "%s", MacroContainer.of(LocationMacro.of(genrule.getBuildTarget()), true)));
    assertThat(
        ((CompositeArg) result).getArgs(),
        Matchers.contains(Matchers.instanceOf(WriteToFileArg.class)));
  }
}
