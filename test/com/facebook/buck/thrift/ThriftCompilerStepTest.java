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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ThriftCompilerStepTest {

  @Test
  public void thriftCompilerStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newInstance();

    // Setup some dummy values for inputs to the ThriftCompilerStep
    ImmutableList<String> compilerPrefix = ImmutableList.of("compiler", "--allow-64-bit");
    Path outputDir = Paths.get("output-dir");
    Path input = Paths.get("test.thrift");
    String language = "cpp";
    ImmutableSet<String> options = ImmutableSet.of("templates");
    ImmutableList<Path> includes = ImmutableList.of(Paths.get("blah-dir"));

    // Create our ThriftCompilerStep to test.
    ThriftCompilerStep thriftCompilerStep = new ThriftCompilerStep(
        projectFilesystem.getRootPath(),
        compilerPrefix,
        outputDir,
        input,
        language,
        options,
        includes);

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .addAll(compilerPrefix)
        .add("--gen", String.format("%s:%s", language, Joiner.on(',').join(options)))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includes, Functions.toStringFunction())))
        .add("-o", outputDir.toString())
        .add(input.toString())
        .build();

    ImmutableList<String> actual = thriftCompilerStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
