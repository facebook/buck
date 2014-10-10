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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileStepTest {

  @Test
  public void cxxLinkStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    // Setup some dummy values for inputs to the CxxCompileStep
    Path compiler = Paths.get("compiler");
    ImmutableList<String> flags =
        ImmutableList.of("-fsanitize=address");
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.cpp");
    ImmutableList<Path> includes = ImmutableList.of(
        Paths.get("foo/bar"),
        Paths.get("test"));
    ImmutableList<Path> systemIncludes = ImmutableList.of(
        Paths.get("/usr/include"),
        Paths.get("/include"));

    // Create our CxxCompileStep to test.
    CxxCompileStep cxxCompileStep = new CxxCompileStep(
        compiler,
        flags,
        output,
        input,
        includes,
        systemIncludes);

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .add(compiler.toString())
        .add("-c")
        .addAll(flags)
        .add("-o", output.toString())
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
    ImmutableList<String> actual = cxxCompileStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
