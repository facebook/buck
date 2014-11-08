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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessStepTest {

  @Test
  public void cxxLinkStepUsesCorrectCommand() {

    // Setup some dummy values for inputs to the CxxPreprocessStep
    Path compiler = Paths.get("compiler");
    ImmutableList<String> flags =
        ImmutableList.of("-Dtest=blah");
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");
    ImmutableList<Path> includes = ImmutableList.of(
        Paths.get("foo/bar"),
        Paths.get("test"));
    ImmutableList<Path> systemIncludes = ImmutableList.of(
        Paths.get("/usr/include"),
        Paths.get("/include"));
    ImmutableMap<Path, Path> replacementPaths = ImmutableMap.of();

    // Create our CxxPreprocessStep to test.
    CxxPreprocessStep cxxPreprocessStep = new CxxPreprocessStep(
        compiler,
        flags,
        output,
        input,
        includes,
        systemIncludes,
        replacementPaths);

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .add(compiler.toString())
        .add("-E")
        .addAll(flags)
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includes, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludes, Functions.toStringFunction())))
        .add(input.toString())
        .build();
    ImmutableList<String> actual = cxxPreprocessStep.getCommand();
    assertEquals(expected, actual);
  }

  @Test
  public void outputProcessor() {
    Path original = Paths.get("buck-out/foo#bar/world.h");
    Path replacement = Paths.get("hello/world.h");

    Function<String, String> processor =
        CxxPreprocessStep.createOutputLineProcessor(ImmutableMap.of(original, replacement));

    // Fixup line marker lines properly.
    assertEquals(
        String.format("# 12 \"%s\"", replacement),
        processor.apply(String.format("# 12 \"%s\"", original)));
    assertEquals(
        String.format("# 12 \"%s\" 2 1", replacement),
        processor.apply(String.format("# 12 \"%s\" 2 1", original)));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertEquals("# 4 \"test.h\"", processor.apply("# 4 \"test.h\""));

    // Don't modify non-line-marker lines.
    assertEquals("int main() {", processor.apply("int main() {"));
  }

  @Test
  public void errorProcessor() {
    Path original = Paths.get("buck-out/foo#bar/world.h");
    Path replacement = Paths.get("hello/world.h");

    Function<String, String> processor =
        CxxPreprocessStep.createErrorLineProcessor(ImmutableMap.of(original, replacement));

    // Fixup lines in included traces.
    assertEquals(
        String.format("In file included from %s:", replacement),
        processor.apply(String.format("In file included from %s:", original)));
    assertEquals(
        String.format("In file included from %s:3:2:", replacement),
        processor.apply(String.format("In file included from %s:3:2:", original)));
    assertEquals(
        String.format("In file included from %s,", replacement),
        processor.apply(String.format("In file included from %s,", original)));
    assertEquals(
        String.format("In file included from %s:7,", replacement),
        processor.apply(String.format("In file included from %s:7,", original)));
    assertEquals(
        String.format("   from %s:", replacement),
        processor.apply(String.format("   from %s:", original)));
    assertEquals(
        String.format("   from %s:3:2:", replacement),
        processor.apply(String.format("   from %s:3:2:", original)));
    assertEquals(
        String.format("   from %s,", replacement),
        processor.apply(String.format("   from %s,", original)));
    assertEquals(
        String.format("   from %s:7,", replacement),
        processor.apply(String.format("   from %s:7,", original)));

    // Fixup lines in error messages.
    assertEquals(
        String.format("%s: something bad", replacement),
        processor.apply(String.format("%s: something bad", original)));
    assertEquals(
        String.format("%s:4: something bad", replacement),
        processor.apply(String.format("%s:4: something bad", original)));
    assertEquals(
        String.format("%s:4:2: something bad", replacement),
        processor.apply(String.format("%s:4:2: something bad", original)));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertEquals("In file included from test.h:", processor.apply("In file included from test.h:"));

    // Don't modify lines without headers.
    assertEquals(" error message!", processor.apply(" error message!"));
  }

}
