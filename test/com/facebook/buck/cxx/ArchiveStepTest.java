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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ArchiveStepTest {

  @Test
  public void testArchiveStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    // Setup dummy values for the archiver, output, and inputs.
    Path archiver = Paths.get("ar");
    Path output = Paths.get("libfoo.a");
    ImmutableList<Path> inputs = ImmutableList.of(
        Paths.get("a.o"),
        Paths.get("b.o"),
        Paths.get("c.o"));

    // Create and archive step.
    ArchiveStep archiveStep = new ArchiveStep(
        archiver,
        output,
        inputs);

    // Verify that the shell command is correct.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .add(archiver.toString())
        .add("rcs")
        .add(output.toString())
        .addAll(Iterables.transform(inputs, Functions.toStringFunction()))
        .build();
    ImmutableList<String> actual = archiveStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
