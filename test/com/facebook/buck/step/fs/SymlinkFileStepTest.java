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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SymlinkFileStepTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testSymlinkFiles() throws IOException {
    ExecutionContext context = ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(tmpDir.getRoot()))
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .build();

    File source = tmpDir.newFile();
    Files.write("foobar", source, Charsets.UTF_8);

    File target = tmpDir.newFile();
    target.delete();

    SymlinkFileStep step = new SymlinkFileStep(source.getName(),
        target.getName());
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
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);

    // Run `ln -s /path/that/does/not/exist dummy` in /tmp.
    ProcessBuilder builder = new ProcessBuilder();
    builder.command("ln", "-s", "/path/that/does/not/exist", "my_symlink");
    File tmp = tmpDir.getRoot();
    builder.directory(tmp);
    Process process = builder.start();
    process.waitFor();

    // Verify that the symlink points to a non-existent file.
    Path symlink = Paths.get(tmp.getAbsolutePath(), "my_symlink");
    assertFalse("exists() should reflect the existence of what the symlink points to",
        symlink.toFile().exists());
    assertTrue("even though exists() is false, isSymbolicLink should be true",
        java.nio.file.Files.isSymbolicLink(symlink));

    // Create an ExecutionContext to return the ProjectFilesystem.
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    ExecutionContext executionContext = createMock(ExecutionContext.class);
    expect(executionContext.getProjectFilesystem()).andReturn(projectFilesystem).anyTimes();
    replay(executionContext);

    tmpDir.newFile("dummy");
    SymlinkFileStep symlinkStep = new SymlinkFileStep(
        /* source */ "dummy",
        /* target */ "my_symlink");
    int exitCode = symlinkStep.execute(executionContext);
    assertEquals(0, exitCode);
    assertTrue(java.nio.file.Files.isSymbolicLink(symlink));
    assertTrue(symlink.toFile().exists());

    verify(executionContext);
  }
}
