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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.util.BuckConstant.SCRATCH_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;

/**
 * Tests {@link BaseCompileToJarStepFactory}
 */
public class BaseCompileToJarStepFactoryTest {

  @Test
  public void testAddPostprocessClassesCommands() {
    ImmutableList<String> postprocessClassesCommands = ImmutableList.of("tool arg1", "tool2");
    Path outputDirectory = SCRATCH_PATH.resolve("android/java/lib__java__classes");
    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.addAll(
        BaseCompileToJarStepFactory.addPostprocessClassesCommands(
            new FakeProjectFilesystem().getRootPath(),
            postprocessClassesCommands,
            outputDirectory));

    ImmutableList<Step> steps = commands.build();
    assertEquals(2, steps.size());

    assertTrue(steps.get(0) instanceof ShellStep);
    ShellStep step0 = (ShellStep) steps.get(0);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool arg1 " + outputDirectory),
        step0.getShellCommand(executionContext));

    assertTrue(steps.get(1) instanceof ShellStep);
    ShellStep step1 = (ShellStep) steps.get(1);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool2 " + outputDirectory),
        step1.getShellCommand(executionContext));
  }
}
