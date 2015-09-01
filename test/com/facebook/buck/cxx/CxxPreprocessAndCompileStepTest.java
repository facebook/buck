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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.util.Escaper;
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
    Path output = Paths.get("test.ii");
    Path depFile = Paths.get("test.dep");
    Path input = Paths.get("test.cpp");

    Path compilationDirectory = Paths.get("compDir");
    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        9,
        File.separatorChar,
        Paths.get("PWD"),
        ImmutableBiMap.of(Paths.get("hello"), Paths.get("SANITIZED")));

    // Create our CxxPreprocessAndCompileStep to test.
    CxxPreprocessAndCompileStep cxxPreprocessStep =
        new CxxPreprocessAndCompileStep(
            Paths.get("."),
            CxxPreprocessAndCompileStep.Operation.PREPROCESS,
            output,
            depFile,
            input,
            CxxSource.Type.CXX,
            Optional.of(compiler),
            Optional.<ImmutableList<String>>absent(),
            replacementPaths,
            sanitizer,
            Optional.<Function<String, Iterable<String>>>absent());

    Function<String, Iterable<String>> processor =
        cxxPreprocessStep.createPreprocessOutputLineProcessor(compilationDirectory);

    // Fixup line marker lines properly.
    assertThat(
        ImmutableList.of(
            String.format("# 12 \"%s\"", Escaper.escapePathForCIncludeString(finalPath))),
        equalTo(processor.apply(String.format("# 12 \"%s\"", original))));
    assertThat(
        ImmutableList.of(
            String.format("# 12 \"%s\" 2 1", Escaper.escapePathForCIncludeString(finalPath))),
        equalTo(processor.apply(String.format("# 12 \"%s\" 2 1", original))));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertThat(ImmutableList.of("# 4 \"test.h\""), equalTo(processor.apply("# 4 \"test.h\"")));

    // Don't modify non-line-marker lines.
    assertThat(ImmutableList.of("int main() {"), equalTo(processor.apply("int main() {")));
  }

  @Test
  public void errorProcessor() {
    Path original = Paths.get("buck-out/foo#bar/world.h");
    Path replacement = Paths.get("hello/world.h");

    // Setup some dummy values for inputs to the CxxPreprocessAndCompileStep
    ImmutableList<String> compiler = ImmutableList.of("compiler");
    Path output = Paths.get("test.ii");
    Path depFile = Paths.get("test.dep");
    Path input = Paths.get("test.cpp");

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
            Paths.get("."),
            CxxPreprocessAndCompileStep.Operation.COMPILE,
            output,
            depFile,
            input,
            CxxSource.Type.CXX,
            Optional.<ImmutableList<String>>absent(),
            Optional.of(compiler),
            replacementPaths,
            sanitizer,
            Optional.<Function<String, Iterable<String>>>absent());

    Function<String, Iterable<String>> processor =
        cxxPreprocessStep.createErrorLineProcessor(compilationDirectory);

    // Fixup lines in included traces.
    assertThat(
        ImmutableList.of(String.format("In file included from %s:", replacement)),
        equalTo(processor.apply(String.format("In file included from %s:", original))));
    assertThat(
        ImmutableList.of(String.format("In file included from %s:3:2:", replacement)),
        equalTo(processor.apply(String.format("In file included from %s:3:2:", original))));
    assertThat(
        ImmutableList.of(String.format("In file included from %s,", replacement)),
        equalTo(processor.apply(String.format("In file included from %s,", original))));
    assertThat(
        ImmutableList.of(String.format("In file included from %s:7,", replacement)),
        equalTo(processor.apply(String.format("In file included from %s:7,", original))));
    assertThat(
        ImmutableList.of(String.format("   from %s:", replacement)),
        equalTo(processor.apply(String.format("   from %s:", original))));
    assertThat(
        ImmutableList.of(String.format("   from %s:3:2:", replacement)),
        equalTo(processor.apply(String.format("   from %s:3:2:", original))));
    assertThat(
        ImmutableList.of(String.format("   from %s,", replacement)),
        equalTo(processor.apply(String.format("   from %s,", original))));
    assertThat(
        ImmutableList.of(String.format("   from %s:7,", replacement)),
        equalTo(processor.apply(String.format("   from %s:7,", original))));

    // Fixup lines in error messages.
    assertThat(
        ImmutableList.of(String.format("%s: something bad", replacement)),
        equalTo(processor.apply(String.format("%s: something bad", original))));
    assertThat(
        ImmutableList.of(String.format("%s:4: something bad", replacement)),
        equalTo(processor.apply(String.format("%s:4: something bad", original))));
    assertThat(
        ImmutableList.of(String.format("%s:4:2: something bad", replacement)),
        equalTo(processor.apply(String.format("%s:4:2: something bad", original))));

    // test.h isn't in the replacement map, so shouldn't be replaced.
    assertThat(
        ImmutableList.of("In file included from test.h:"),
        equalTo(processor.apply("In file included from test.h:")));

    // Don't modify lines without headers.
    assertThat(
        ImmutableList.of(" error message!"),
        equalTo(processor.apply(" error message!")));
  }

}
