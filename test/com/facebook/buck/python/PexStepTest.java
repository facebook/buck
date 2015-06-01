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

package com.facebook.buck.python;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;

import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PexStepTest {
  private static final Path PYTHON_PATH = Paths.get("/usr/local/bin/python");
  private static final Path PEXPY_PATH = Paths.get("pex.py");
  private static final Path TEMP_PATH = Paths.get("/tmp/");
  private static final Path DEST_PATH = Paths.get("/dest");
  private static final String ENTRY_POINT = "entry_point.main";

  @Test
  public void testCommandLine() {
    PexStep step = new PexStep(
        PEXPY_PATH, PYTHON_PATH, TEMP_PATH, DEST_PATH, ENTRY_POINT,
        /* modules */ ImmutableMap.<Path, Path>of(),
        /* resources */ ImmutableMap.<Path, Path>of(),
        /* nativeLibraries */ ImmutableMap.<Path, Path>of(),
        /* zipSafe */ true);
    String command = Joiner.on(" ").join(
        step.getShellCommandInternal(TestExecutionContext.newInstance()));

    assertThat(command, startsWith(PYTHON_PATH + " " + PEXPY_PATH));
    assertThat(command, containsString("--python " + PYTHON_PATH));
    assertThat(command, containsString("--entry-point " + ENTRY_POINT));
    assertThat(command, endsWith(" " + DEST_PATH));
  }

  @Test
  public void testCommandLineNoZipSafe() {
    PexStep step = new PexStep(
        PEXPY_PATH, PYTHON_PATH, TEMP_PATH, DEST_PATH, ENTRY_POINT,
        /* modules */ ImmutableMap.<Path, Path>of(),
        /* resources */ ImmutableMap.<Path, Path>of(),
        /* nativeLibraries */ ImmutableMap.<Path, Path>of(),
        /* zipSafe */ false);
    String command = Joiner.on(" ").join(
        step.getShellCommandInternal(TestExecutionContext.newInstance()));

    assertThat(command, containsString("--no-zip-safe"));
  }

}
