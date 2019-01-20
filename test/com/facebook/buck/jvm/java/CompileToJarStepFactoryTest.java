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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Test;

/** Tests {@link CompileToJarStepFactory} */
public class CompileToJarStepFactoryTest {

  @Test
  public void testAddPostprocessClassesCommands() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    String androidBootClassPath = filesystem.resolve("android.jar").toString();
    ImmutableList<String> postprocessClassesCommands = ImmutableList.of("tool arg1", "tool2");
    Path outputDirectory =
        filesystem.getBuckPaths().getScratchDir().resolve("android/java/lib__java__classes");
    ImmutableSortedSet<Path> classpathEntries =
        ImmutableSortedSet.<Path>naturalOrder()
            .add(filesystem.resolve("rt.jar"))
            .add(filesystem.resolve("dep.jar"))
            .build();
    ExecutionContext executionContext = TestExecutionContext.newInstance();

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.addAll(
        CompileToJarStepFactory.addPostprocessClassesCommands(
            new FakeProjectFilesystem(),
            postprocessClassesCommands,
            outputDirectory,
            classpathEntries,
            Optional.of(androidBootClassPath)));

    ImmutableList<Step> steps = commands.build();
    assertEquals(2, steps.size());

    assertTrue(steps.get(0) instanceof ShellStep);
    ShellStep step0 = (ShellStep) steps.get(0);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool arg1 " + outputDirectory),
        step0.getShellCommand(executionContext));
    assertEquals(
        ImmutableMap.of(
            "COMPILATION_BOOTCLASSPATH",
            androidBootClassPath,
            "COMPILATION_CLASSPATH",
            Joiner.on(':').join(Iterables.transform(classpathEntries, filesystem::resolve))),
        step0.getEnvironmentVariables(executionContext));

    assertTrue(steps.get(1) instanceof ShellStep);
    ShellStep step1 = (ShellStep) steps.get(1);
    assertEquals(
        ImmutableList.of("bash", "-c", "tool2 " + outputDirectory),
        step1.getShellCommand(executionContext));
    assertEquals(
        ImmutableMap.of(
            "COMPILATION_BOOTCLASSPATH",
            androidBootClassPath,
            "COMPILATION_CLASSPATH",
            Joiner.on(':').join(Iterables.transform(classpathEntries, filesystem::resolve))),
        step1.getEnvironmentVariables(executionContext));
  }
}
