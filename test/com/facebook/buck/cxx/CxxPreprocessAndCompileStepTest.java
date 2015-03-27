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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessAndCompileStepTest {

  @Test
  public void outputProcessor() {
    Path original = Paths.get("buck-out/foo#bar/world.h");
    ImmutableMap<Path, Path> replacementPaths =
        ImmutableMap.of(original, Paths.get("hello/////world.h"));
    Path finalPath = Paths.get("SANITIZED/world.h");

    // Setup some dummy values for inputs to the CxxPreprocessAndCompileStep
    ImmutableList<String> compiler = ImmutableList.of("compiler");
    ImmutableList<String> flags = ImmutableList.of("-Dtest=blah");
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");

    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler);
    cmd.add(CxxPreprocessAndCompileStep.Operation.PREPROCESS.getFlag());
    cmd.addAll(flags);
    cmd.add(input.toString());

    Path compilationDirectory = Paths.get("compDir");
    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        9,
        File.separatorChar,
        "PWD",
        ImmutableBiMap.of(Paths.get("hello"), "SANITIZED"));

    // Create our CxxPreprocessAndCompileStep to test.
    CxxPreprocessAndCompileStep cxxPreprocessStep =
        new CxxPreprocessAndCompileStep(
            CxxPreprocessAndCompileStep.Operation.PREPROCESS,
            output,
            input,
            cmd.build(),
            replacementPaths,
            Optional.of(sanitizer));

    Function<String, String> processor =
        cxxPreprocessStep.createPreprocessOutputLineProcessor(compilationDirectory);

    // Fixup line marker lines properly.
    assertEquals(
        String.format("# 12 \"%s\"", finalPath),
        processor.apply(String.format("# 12 \"%s\"", original)));
    assertEquals(
        String.format("# 12 \"%s\" 2 1", finalPath),
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

    // Setup some dummy values for inputs to the CxxPreprocessAndCompileStep
    ImmutableList<String> compiler = ImmutableList.of("compiler");
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");

    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler);
    cmd.add(CxxPreprocessAndCompileStep.Operation.COMPILE.getFlag());
    cmd.add("-o", output.toString());
    cmd.add(input.toString());

    ImmutableMap<Path, Path> replacementPaths = ImmutableMap.of(original, replacement);

    Path compilationDirectory = Paths.get("compDir");
    Path sanitizedDir = Paths.get("hello");
    Path unsanitizedDir = Paths.get("buck-out/foo#bar");
    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        unsanitizedDir.toString().length(),
        File.separatorChar,
        compilationDirectory,
        ImmutableBiMap.of(unsanitizedDir, sanitizedDir));

    // Create our CxxPreprocessAndCompileStep to test.
    CxxPreprocessAndCompileStep cxxPreprocessStep =
        new CxxPreprocessAndCompileStep(
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            output,
            input,
            cmd.build(),
            replacementPaths,
            Optional.of(sanitizer));

    Function<String, String> processor =
        cxxPreprocessStep.createErrorLineProcessor(compilationDirectory);

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
