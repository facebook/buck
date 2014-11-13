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

import com.google.common.base.Optional;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileStepTest {

  @Test
  public void cxxLinkStepUsesCorrectCommand() {

    // Setup some dummy values for inputs to the CxxCompileStep
    Path compiler = Paths.get("compiler");
    ImmutableList<String> flags =
        ImmutableList.of("-fsanitize=address");
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.cpp");

    // Create our CxxCompileStep to test.
    CxxCompileStep cxxCompileStep = new CxxCompileStep(
        compiler,
        flags,
        output,
        input,
        Optional.<DebugPathSanitizer>absent());

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .add(compiler.toString())
        .add("-c")
        .addAll(flags)
        .add("-o", output.toString())
        .add(input.toString())
        .build();
    ImmutableList<String> actual = cxxCompileStep.getCommand();
    assertEquals(expected, actual);
  }

  @Test
  public void errorProcessor() {

    // Setup some dummy values for inputs to the CxxCompileStep
    Path compiler = Paths.get("compiler");
    ImmutableList<String> flags =
        ImmutableList.of("-fsanitize=address");
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.cpp");

    String sanitized = "hello////////////world.h";
    String unsanitized = "buck-out/foo#bar/world.h";

    Path compilationDirectory = Paths.get("compDir");
    Path sanitizedDir = Paths.get("hello");
    Path unsanitizedDir = Paths.get("buck-out/foo#bar");
    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        unsanitizedDir.toString().length(),
        File.separatorChar,
        compilationDirectory,
        ImmutableBiMap.of(unsanitizedDir, sanitizedDir));

    // Create our CxxCompileStep to test.
    CxxCompileStep cxxCompileStep = new CxxCompileStep(
        compiler,
        flags,
        output,
        input,
        Optional.of(sanitizer));

    Function<String, String> processor =
        cxxCompileStep.createErrorLineProcessor(compilationDirectory);

    // Fixup lines in included traces.
    assertEquals(
        String.format("In file included from %s:", unsanitized),
        processor.apply(String.format("In file included from %s:", sanitized)));
    assertEquals(
        String.format("In file included from %s:3:2:", unsanitized),
        processor.apply(String.format("In file included from %s:3:2:", sanitized)));
    assertEquals(
        String.format("In file included from %s,", unsanitized),
        processor.apply(String.format("In file included from %s,", sanitized)));
    assertEquals(
        String.format("In file included from %s:7,", unsanitized),
        processor.apply(String.format("In file included from %s:7,", sanitized)));
    assertEquals(
        String.format("   from %s:", unsanitized),
        processor.apply(String.format("   from %s:", sanitized)));
    assertEquals(
        String.format("   from %s:3:2:", unsanitized),
        processor.apply(String.format("   from %s:3:2:", sanitized)));
    assertEquals(
        String.format("   from %s,", unsanitized),
        processor.apply(String.format("   from %s,", sanitized)));
    assertEquals(
        String.format("   from %s:7,", unsanitized),
        processor.apply(String.format("   from %s:7,", sanitized)));

    // Fixup lines in error messages.
    assertEquals(
        String.format("%s: something bad", unsanitized),
        processor.apply(String.format("%s: something bad", sanitized)));
    assertEquals(
        String.format("%s:4: something bad", unsanitized),
        processor.apply(String.format("%s:4: something bad", sanitized)));
    assertEquals(
        String.format("%s:4:2: something bad", unsanitized),
        processor.apply(String.format("%s:4:2: something bad", sanitized)));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertEquals("In file included from test.h:", processor.apply("In file included from test.h:"));

    // Don't modify lines without headers.
    assertEquals(" error message!", processor.apply(" error message!"));
  }

}
