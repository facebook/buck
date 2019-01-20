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

package com.facebook.buck.android;

import static com.facebook.buck.util.Verbosity.COMMANDS_AND_SPECIAL_OUTPUT;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.cli.VerbosityParser;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DxStepTest {

  private static final String BASE_DX_PREFIX = Paths.get("/usr/bin/dx").toString();

  private static final String EXPECTED_DX_PREFIX = Paths.get("/usr/bin/dx") + " --dex";

  private static final Path SAMPLE_OUTPUT_PATH =
      Paths.get(".").toAbsolutePath().normalize().resolve("buck-out/gen/classes.dex");

  private static final ImmutableSet<Path> SAMPLE_FILES_TO_DEX =
      ImmutableSet.of(Paths.get("buck-out/gen/foo.dex.jar"), Paths.get("buck-out/gen/bar.dex.jar"));

  private AndroidPlatformTarget androidPlatformTarget;

  @Before
  public void setUp() {
    androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            () -> new SimpleTool(""),
            () -> new SimpleTool(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("/usr/bin/dx"),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));
  }

  @Test
  public void testDxCommandNoOptimizeNoJumbo() throws IOException {
    // Context with --verbose 2.
    try (ExecutionContext context = createExecutionContext(2)) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.of(Option.NO_OPTIMIZE),
              DxStep.DX);

      String expected =
          String.format(
              "%s --no-optimize --output %s %s",
              EXPECTED_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "--no-optimize should be present, but --force-jumbo should not.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testDxCommandOptimizeNoJumbo() throws IOException {
    // Context with --verbose 2.
    try (ExecutionContext context = createExecutionContext(2)) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(filesystem, androidPlatformTarget, SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

      String expected =
          String.format(
              "%s --output %s %s",
              EXPECTED_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "Neither --no-optimize nor --force-jumbo should be present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testDxCommandNoOptimizeForceJumbo() throws IOException {
    // Context with --verbose 2.
    try (ExecutionContext context = createExecutionContext(2)) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.of(DxStep.Option.NO_OPTIMIZE, DxStep.Option.FORCE_JUMBO),
              DxStep.DX);

      String expected =
          String.format(
              "%s --no-optimize --force-jumbo --output %s %s",
              EXPECTED_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "Both --no-optimize and --force-jumbo should be present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testVerbose3AddsStatisticsFlag() throws IOException {
    // Context with --verbose 3.
    try (ExecutionContext context = createExecutionContext(COMMANDS_AND_SPECIAL_OUTPUT.ordinal())) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(filesystem, androidPlatformTarget, SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

      String expected =
          String.format(
              "%s --statistics --output %s %s",
              EXPECTED_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "Ensure that the --statistics flag is present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);

      assertTrue(
          "Should print stdout to show statistics.", dx.shouldPrintStdout(context.getVerbosity()));
      assertTrue(
          "Should print stderr to show statistics.", dx.shouldPrintStderr(context.getVerbosity()));
    }
  }

  @Test
  public void testVerbose10AddsVerboseFlagToDx() throws IOException {
    // Context with --verbose 10.
    try (ExecutionContext context = createExecutionContext(10)) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(filesystem, androidPlatformTarget, SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

      String expected =
          String.format(
              "%s --statistics --verbose --output %s %s",
              EXPECTED_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "Ensure that the --statistics flag is present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);

      assertTrue(
          "Should print stdout since `dx --verbose` is enabled.",
          dx.shouldPrintStdout(context.getVerbosity()));
      assertTrue(
          "Should print stdout since `dx --verbose` is enabled.",
          dx.shouldPrintStderr(context.getVerbosity()));
    }
  }

  @Test
  public void testOverridenMaxHeapSize() throws IOException {
    try (ExecutionContext context = createExecutionContext(2)) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      DxStep dx =
          new DxStep(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.noneOf(DxStep.Option.class),
              Optional.of("2g"),
              DxStep.DX,
              false);

      String expected =
          String.format(
              "%s -JXmx2g --dex --output %s %s",
              BASE_DX_PREFIX,
              SAMPLE_OUTPUT_PATH,
              Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, filesystem::resolve)));
      MoreAsserts.assertShellCommands(
          "Ensure that the -JXmx flag is present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  private ExecutionContext createExecutionContext(int verbosityLevel) {
    Verbosity verbosity = VerbosityParser.getVerbosityForLevel(verbosityLevel);
    TestConsole console = new TestConsole(verbosity);
    return TestExecutionContext.newBuilder().setConsole(console).build();
  }
}
