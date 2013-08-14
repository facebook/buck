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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class SymlinkFileStepTest {
  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();


  @Test
  public void testSymlinkFiles() throws IOException {
    ExecutionContext context = ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(tmpDir.getRoot()))
        .setEventBus(BuckEventBusFactory.newInstance())
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
}
