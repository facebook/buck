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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CopyStepTest {

  private ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

  @Test
  public void testGetShellCommandInternalPath() {
    Path source = Paths.get("path/to/source.txt");
    Path destination = Paths.get("path/to/destination.txt");
    CopyStep copyCommand = CopyStep.forFile(filesystem, source, destination);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertFalse(copyCommand.isRecursive());
  }

  @Test
  public void testGetShellCommandInternal() {
    Path source = Paths.get("path/to/source.txt");
    Path destination = Paths.get("path/to/destination.txt");
    CopyStep copyCommand = CopyStep.forFile(filesystem, source, destination);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertFalse(copyCommand.isRecursive());
  }

  @Test
  public void testGetShellCommandInternalWithRecurse() {
    Path source = Paths.get("path/to/source");
    Path destination = Paths.get("path/to/destination");
    CopyStep copyCommand = CopyStep.forDirectory(
        filesystem,
        source,
        destination,
        CopyStep.DirectoryMode.CONTENTS_ONLY);
    assertEquals(source, copyCommand.getSource());
    assertEquals(destination, copyCommand.getDestination());
    assertTrue(copyCommand.isRecursive());
  }

  @Test
  public void testGetShortName() {
    CopyStep copyCommand = CopyStep.forFile(filesystem, Paths.get("here"), Paths.get("there"));
    assertEquals("cp", copyCommand.getShortName());
  }

}
