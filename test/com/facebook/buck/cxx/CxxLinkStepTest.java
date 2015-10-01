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

public class CxxLinkStepTest {

  @Test
  public void cxxLinkStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newInstance();

    ImmutableList<String> linker = ImmutableList.of("linker");

    // Create our CxxLinkStep to test.
    CxxLinkStep cxxLinkStep = new CxxLinkStep(
        projectFilesystem.getRootPath(),
        linker,
        projectFilesystem.getRootPath().resolve("argfile.txt"));

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .addAll(linker)
        .add("@" + projectFilesystem.getRootPath().resolve("argfile.txt").toString())
        .build();
    ImmutableList<String> actual = cxxLinkStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
