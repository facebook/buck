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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class LexStepTest {

  @Test
  public void lexStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<String> lexPrefix = ImmutableList.of("lex");
    ImmutableList<String> flags = ImmutableList.of("-flag");
    Path outputSource = Paths.get("outputSource");
    Path outputHeader = Paths.get("outputHeader");
    Path input = Paths.get("input");

    // Create our CxxLinkStep to test.
    LexStep lexStep = new LexStep(
        lexPrefix,
        flags,
        outputSource,
        outputHeader,
        input);

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .addAll(lexPrefix)
        .addAll(flags)
        .add("--outfile=" + outputSource.toString())
        .add("--header-file=" + outputHeader.toString())
        .add(input.toString())
        .build();
    ImmutableList<String> actual = lexStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
