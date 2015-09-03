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
import static org.junit.Assert.assertSame;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class CxxTestStepTest {

  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private Path exitCode;
  private Path output;
  private ExecutionContext context;
  private FakeProjectFilesystem filesystem;

  private static int readExitCode(Path file) throws IOException {
    try (FileInputStream fileIn = new FileInputStream(file.toFile());
         ObjectInputStream objIn = new ObjectInputStream(fileIn)) {
      return objIn.readInt();
    }
  }

  private static void assertContents(Path file, String contents) throws IOException {
    assertEquals(contents, new String(Files.readAllBytes(file)));
  }

  @Before
  public void setUp() throws IOException {
    exitCode = tmpDir.newFile("exitCode").toPath();
    output = tmpDir.newFile("output").toPath();
    context = TestExecutionContext.newInstance();
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void success() throws IOException, InterruptedException {
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            ImmutableList.of("true"),
            ImmutableMap.<String, String>of(),
            exitCode,
            output);
    step.execute(context);
    assertSame(0, readExitCode(exitCode));
    assertContents(output, "");
  }

  @Test
  public void failure() throws IOException, InterruptedException {
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            ImmutableList.of("false"),
            ImmutableMap.<String, String>of(),
            exitCode,
            output);
    step.execute(context);
    assertSame(1, readExitCode(exitCode));
    assertContents(output, "");
  }

  @Test
  public void output() throws IOException, InterruptedException {
    String stdout = "hello world";
    CxxTestStep step =
        new CxxTestStep(
            filesystem,
            ImmutableList.of("echo", stdout),
            ImmutableMap.<String, String>of(),
            exitCode,
            output);
    step.execute(context);
    assertSame(0, readExitCode(exitCode));
    assertContents(output, stdout + System.lineSeparator());
  }

}
