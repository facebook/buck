/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SymlinkFileStepTest {

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testAbsoluteSymlinkFiles() throws InterruptedException, IOException {
    ExecutionContext context = TestExecutionContext.newInstance();

    File source = tmpDir.newFile();
    Files.write("foobar", source, Charsets.UTF_8);

    File target = tmpDir.newFile();
    target.delete();

    SymlinkFileStep step =
        SymlinkFileStep.builder()
            .setFilesystem(
                TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath()))
            .setExistingFile(Paths.get(source.getName()))
            .setDesiredLink(Paths.get(target.getName()))
            .build();
    step.execute(context);
    // Run twice to ensure we can overwrite an existing symlink
    step.execute(context);

    assertTrue(target.exists());
    assertEquals("foobar", Files.readFirstLine(target, Charsets.UTF_8));

    // Modify the original file and see if the linked file changes as well.
    Files.write("new", source, Charsets.UTF_8);
    assertEquals("new", Files.readFirstLine(target, Charsets.UTF_8));
  }

  @Test
  public void testReplaceMalformedSymlink() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    // Run `ln -s /path/that/does/not/exist dummy` in /tmp.
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("ln", "-s", "/path/that/does/not/exist", "my_symlink"))
            .setDirectory(tmpDir.getRoot().toPath())
            .build();
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    executor.launchAndExecute(params);

    // Verify that the symlink points to a non-existent file.
    Path symlink = Paths.get(tmpDir.getRoot().getAbsolutePath(), "my_symlink");
    assertFalse(
        "exists() should reflect the existence of what the symlink points to",
        symlink.toFile().exists());
    assertTrue(
        "even though exists() is false, isSymbolicLink should be true",
        java.nio.file.Files.isSymbolicLink(symlink));

    // Create an ExecutionContext to return the ProjectFilesystem.
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath());
    ExecutionContext executionContext = TestExecutionContext.newInstance();

    tmpDir.newFile("dummy");
    SymlinkFileStep symlinkStep =
        SymlinkFileStep.builder()
            .setFilesystem(projectFilesystem)
            .setExistingFile(Paths.get("dummy"))
            .setDesiredLink(Paths.get("my_symlink"))
            .build();
    int exitCode = symlinkStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
    assertTrue(java.nio.file.Files.isSymbolicLink(symlink));
    assertTrue(symlink.toFile().exists());
  }
}
