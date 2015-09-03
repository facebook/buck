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
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;

import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class PexStepTest {

  private static final Path PYTHON_PATH = Paths.get("/usr/local/bin/python");
  private static final ImmutableList<String> PEX_COMMAND = ImmutableList.of();
  private static final Path TEMP_PATH = Paths.get("/tmp/");
  private static final Path DEST_PATH = Paths.get("/dest");
  private static final String ENTRY_POINT = "entry_point.main";

  private static final ImmutableMap<Path, Path> MODULES = ImmutableMap.of(
      Paths.get("m"), Paths.get("/src/m"));
  private static final ImmutableMap<Path, Path> RESOURCES = ImmutableMap.of(
      Paths.get("r"), Paths.get("/src/r"));
  private static final ImmutableMap<Path, Path> NATIVE_LIBRARIES = ImmutableMap.of(
      Paths.get("n.so"), Paths.get("/src/n.so"));
  private static final ImmutableSet<Path> PREBUILT_LIBRARIES = ImmutableSet.of(
      Paths.get("/src/p.egg"));


  @Test
  public void testCommandLine() {
    PexStep step =
        new PexStep(
            new FakeProjectFilesystem(),
            PEX_COMMAND,
            PYTHON_PATH,
            TEMP_PATH,
            DEST_PATH,
            ENTRY_POINT,
            MODULES,
            RESOURCES,
            NATIVE_LIBRARIES,
            PREBUILT_LIBRARIES,
            /* zipSafe */ true);
    String command = Joiner.on(" ").join(
        step.getShellCommandInternal(TestExecutionContext.newInstance()));

    assertThat(command, startsWith(Joiner.on(" ").join(PEX_COMMAND)));
    assertThat(command, containsString("--python " + PYTHON_PATH));
    assertThat(command, containsString("--entry-point " + ENTRY_POINT));
    assertThat(command, endsWith(" " + DEST_PATH));
  }

  @Test
  public void testCommandLineNoZipSafe() {
    PexStep step =
        new PexStep(
            new FakeProjectFilesystem(),
            PEX_COMMAND,
            PYTHON_PATH,
            TEMP_PATH,
            DEST_PATH,
            ENTRY_POINT,
            MODULES,
            RESOURCES,
            NATIVE_LIBRARIES,
            PREBUILT_LIBRARIES,
            /* zipSafe */ false);
    String command = Joiner.on(" ").join(
        step.getShellCommandInternal(TestExecutionContext.newInstance()));

    assertThat(command, containsString("--no-zip-safe"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCommandStdin() throws IOException {
    PexStep step =
        new PexStep(
            new FakeProjectFilesystem(),
            PEX_COMMAND,
            PYTHON_PATH,
            TEMP_PATH,
            DEST_PATH,
            ENTRY_POINT,
            MODULES,
            RESOURCES,
            NATIVE_LIBRARIES,
            PREBUILT_LIBRARIES,
            /* zipSafe */ true);

    Map<String, Object> args = new ObjectMapper().readValue(
        step.getStdin(TestExecutionContext.newInstance()).get(),
        Map.class);
    assertThat(
        (Map<String, String>) args.get("modules"),
        hasEntry(Paths.get("m").toString(), Paths.get("/src/m").toString()));
    assertThat(
        (Map<String, String>) args.get("resources"),
        hasEntry(Paths.get("r").toString(), Paths.get("/src/r").toString()));
    assertThat(
        (Map<String, String>) args.get("nativeLibraries"),
        hasEntry(Paths.get("n.so").toString(), Paths.get("/src/n.so").toString()));
    assertThat(
        (List<String>) args.get("prebuiltLibraries"),
        hasItem(Paths.get("/src/p.egg").toString()));
  }

  @Test
  public void testArgs() {
    PexStep step =
        new PexStep(
            new FakeProjectFilesystem(),
            ImmutableList.<String>builder()
                .add("build")
                .add("--some", "--args")
                .build(),
            PYTHON_PATH,
            TEMP_PATH,
            DEST_PATH,
            ENTRY_POINT,
            MODULES,
            RESOURCES,
            NATIVE_LIBRARIES,
            PREBUILT_LIBRARIES,
            /* zipSafe */ true);
    assertThat(
        step.getShellCommandInternal(TestExecutionContext.newInstance()),
        hasItems("--some", "--args"));
  }

}
