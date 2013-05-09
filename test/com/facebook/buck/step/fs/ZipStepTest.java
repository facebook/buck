/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Verbosity;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

public class ZipStepTest {

  @Test
  public void testGetShellCommandInternalOnZipCommandWithSpecifiedDirectory() {
    final String zipFile = "/path/to/file.zip";
    final int compressionLevel = ZipStep.DEFAULT_COMPRESSION_LEVEL;

    final File directory = new File("/some/other/path");

    ExecutionContext context = createMock(ExecutionContext.class);
    // This will trigger having the -q argument.
    expect(context.getVerbosity()).andReturn(Verbosity.STANDARD_INFORMATION);
    replay(context);

    List<String> expectedShellCommand = new ImmutableList.Builder<String>()
        .add("zip")
        .add("-q")
        .add("-X")
        .add("-r")
        .add("-" + compressionLevel)
        .add(zipFile)
        .add("-i*")
        .add(".")
        .build();

    ZipStep command = new ZipStep(zipFile, directory);

    // Assert that the command has been constructed with the right arguments.
    MoreAsserts.assertListEquals(expectedShellCommand,
        command.getShellCommand(context));

    // Assert that the desired directory is saved with the ZipCommand as a working directory.
    assertEquals(directory, command.getWorkingDirectory());

    verify(context);
  }

  @Test
  public void testGetShellCommandInternalOnZipCommandWithSpecifiedPaths() {
    final String zipFile = "/path/to/file.zip";
    final Set<String> paths = ImmutableSet.of("a/path", "another.path");
    final int compressionLevel = 7;
    final File workingDirectory = new File("/some/other/path");

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.getVerbosity()).andReturn(Verbosity.SILENT);
    replay(context);

    List<String> expectedShellCommand = new ImmutableList.Builder<String>()
        .add("zip")
        .add("-u")
        .add("-qq")
        .add("-X")
        .add("-r")
        .add("-" + compressionLevel)
        .add("-j")
        .add(zipFile)
        .addAll(paths)
        .build();

    ZipStep command = new ZipStep(
        ZipStep.Mode.UPDATE,
        zipFile,
        paths,
        true,
        compressionLevel,
        workingDirectory);

    // Assert that the command has been constructed with the right arguments.
    MoreAsserts.assertListEquals(expectedShellCommand,
        command.getShellCommand(context));

    // Assert that the desired working directory is saved with the command.
    assertEquals(workingDirectory, command.getWorkingDirectory());

    verify(context);
  }
}
