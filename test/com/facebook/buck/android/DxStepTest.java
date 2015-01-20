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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.cli.VerbosityParser;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

public class DxStepTest extends EasyMockSupport {

  private static final String EXPECTED_DX_PREFIX = "/usr/bin/dx --dex";

  private static final Path SAMPLE_OUTPUT_PATH =
      Paths.get(".").toAbsolutePath().normalize().resolve("buck-out/gen/classes.dex");

  private static final ImmutableSet<Path> SAMPLE_FILES_TO_DEX = ImmutableSet.of(
      Paths.get("buck-out/gen/foo.dex.jar"),
      Paths.get("buck-out/gen/bar.dex.jar"));

  private Optional<AndroidPlatformTarget> androidPlatformTargetOptional;

  @Before
  public void setUp() {
    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    EasyMock.expect(androidPlatformTarget.getDxExecutable()).andReturn(Paths.get("/usr/bin/dx"));
    androidPlatformTargetOptional = Optional.of(androidPlatformTarget);
    replayAll();
  }

  @Test
  public void testDxCommandNoOptimizeNoJumbo() {
    // Context with --verbose 2.
    ExecutionContext context = createExecutionContext(2);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH,
        SAMPLE_FILES_TO_DEX,
        EnumSet.of(Option.NO_OPTIMIZE));

    String expected = String.format("%s --no-optimize --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "--no-optimize should be present, but --force-jumbo should not.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);
    verifyAll();
  }

  @Test
  public void testDxCommandOptimizeNoJumbo() {
    // Context with --verbose 2.
    ExecutionContext context = createExecutionContext(2);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

    String expected = String.format("%s --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "Neither --no-optimize nor --force-jumbo should be present.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);
    verifyAll();
  }

  @Test
  public void testDxCommandNoOptimizeForceJumbo() {
    // Context with --verbose 2.
    ExecutionContext context = createExecutionContext(2);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH,
        SAMPLE_FILES_TO_DEX,
        EnumSet.of(DxStep.Option.NO_OPTIMIZE, DxStep.Option.FORCE_JUMBO));

    String expected = String.format(
        "%s --no-optimize --force-jumbo --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "Both --no-optimize and --force-jumbo should be present.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);
    verifyAll();
  }

  @Test
  public void testVerbose3AddsStatisticsFlag() {
    // Context with --verbose 3.
    ExecutionContext context = createExecutionContext(3);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

    String expected = String.format("%s --statistics --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "Ensure that the --statistics flag is present.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);

    assertTrue("Should print stdout to show statistics.",
        dx.shouldPrintStdout(context.getVerbosity()));
    assertTrue("Should print stderr to show statistics.",
        dx.shouldPrintStderr(context.getVerbosity()));
    verifyAll();
  }

  @Test
  public void testVerbose10AddsVerboseFlagToDx() {
    // Context with --verbose 10.
    ExecutionContext context = createExecutionContext(10);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

    String expected = String.format(
        "%s --statistics --verbose --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "Ensure that the --statistics flag is present.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);

    assertTrue("Should print stdout since `dx --verbose` is enabled.",
        dx.shouldPrintStdout(context.getVerbosity()));
    assertTrue("Should print stdout since `dx --verbose` is enabled.",
        dx.shouldPrintStderr(context.getVerbosity()));
    verifyAll();
  }

  @Test
  public void testUseCustomDxOption() {
    // Context with --verbose 2.
    ExecutionContext context = createExecutionContext(2);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH,
        SAMPLE_FILES_TO_DEX,
        EnumSet.noneOf(Option.class),
        new Supplier<String>() {
          @Override
          public String get() {
            return "/home/mbolin/dx";
          }
        });

    String expected = String.format("%s --output %s %s",
        EXPECTED_DX_PREFIX.replace("/usr/bin/dx", "/home/mbolin/dx"),
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "/home/mbolin/dx should be used instead of /usr/bin/dx.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);
    verifyAll();
  }

  @Test
  public void testUseCustomDxOptionWithNullSupplier() {
    // Context with --verbose 2.
    ExecutionContext context = createExecutionContext(2);
    Function<Path, Path> pathAbsolutifier = context.getProjectFilesystem().getAbsolutifier();

    DxStep dx = new DxStep(SAMPLE_OUTPUT_PATH,
        SAMPLE_FILES_TO_DEX,
        EnumSet.noneOf(Option.class),
        new Supplier<String>() {
          @Override
          public String get() {
            return null;
          }
        });

    String expected = String.format("%s --output %s %s",
        EXPECTED_DX_PREFIX,
        SAMPLE_OUTPUT_PATH,
        Joiner.on(' ').join(Iterables.transform(SAMPLE_FILES_TO_DEX, pathAbsolutifier)));
    MoreAsserts.assertShellCommands(
        "Should fall back to /usr/bin/dx even though USE_CUSTOM_DX_IF_AVAILABLE is used.",
        ImmutableList.of(expected),
        ImmutableList.<Step>of(dx),
        context);
    verifyAll();
  }

  private ExecutionContext createExecutionContext(int verbosityLevel) {
    Verbosity verbosity = VerbosityParser.getVerbosityForLevel(verbosityLevel);
    TestConsole console = new TestConsole(verbosity);
    return TestExecutionContext.newBuilder()
        .setConsole(console)
        .setAndroidPlatformTarget(androidPlatformTargetOptional)
        .build();
  }
}
