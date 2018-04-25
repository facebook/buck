/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.LdflagsStaticMacro;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxGenruleFilterAndTargetsMacroTypeCoercerTest {

  @Test
  public void testNoPattern() throws CoerceFailedException {
    Path basePath = Paths.get("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxGenruleFilterAndTargetsMacroTypeCoercer<CppFlagsMacro> coercer =
        new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
            Optional.empty(),
            new ListTypeCoercer<>(new BuildTargetTypeCoercer()),
            CppFlagsMacro.class,
            CppFlagsMacro::of);
    CppFlagsMacro result =
        coercer.coerce(createCellRoots(filesystem), filesystem, basePath, ImmutableList.of("//:a"));
    assertThat(
        result,
        Matchers.equalTo(
            CppFlagsMacro.of(
                Optional.empty(), ImmutableList.of(BuildTargetFactory.newInstance("//:a")))));
  }

  @Test
  public void testPattern() throws CoerceFailedException {
    Path basePath = Paths.get("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxGenruleFilterAndTargetsMacroTypeCoercer<LdflagsStaticMacro> coercer =
        new CxxGenruleFilterAndTargetsMacroTypeCoercer<>(
            Optional.of(new PatternTypeCoercer()),
            new ListTypeCoercer<>(new BuildTargetTypeCoercer()),
            LdflagsStaticMacro.class,
            LdflagsStaticMacro::of);
    LdflagsStaticMacro result =
        coercer.coerce(
            createCellRoots(filesystem), filesystem, basePath, ImmutableList.of("hello", "//:a"));
    assertThat(result.getFilter().map(Pattern::pattern), Matchers.equalTo(Optional.of("hello")));
    assertThat(
        result.getTargets(),
        Matchers.equalTo(ImmutableList.of(BuildTargetFactory.newInstance("//:a"))));
  }
}
