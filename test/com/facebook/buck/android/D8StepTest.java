/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.D8Step.Option;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class D8StepTest {

  private static final Path SAMPLE_OUTPUT_PATH =
      Paths.get(".").toAbsolutePath().normalize().resolve("buck-out/gen/classes.dex");

  private static final ImmutableSet<Path> SAMPLE_FILES_TO_DEX =
      ImmutableSet.of(Paths.get("buck-out/gen/foo.dex.jar"), Paths.get("buck-out/gen/bar.dex.jar"));

  private AndroidPlatformTarget androidPlatformTarget;

  @Before
  public void setUp() {
    androidPlatformTarget = AndroidTestUtils.createAndroidPlatformTarget();
  }

  @Test
  public void testDxCommandNoOptimizeNoJumbo() throws IOException {
    try (StepExecutionContext context = TestExecutionContext.newInstance()) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      D8Step dx =
          new D8Step(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.of(Option.NO_OPTIMIZE),
              D8Step.D8);

      String expected =
          String.format(
              "d8 --output %s --debug --lib %s %s",
              SAMPLE_OUTPUT_PATH,
              androidPlatformTarget.getAndroidJar(),
              Joiner.on(' ')
                  .join(
                      SAMPLE_FILES_TO_DEX.stream()
                          .map(filesystem::resolve)
                          .collect(Collectors.toList())));
      MoreAsserts.assertSteps(
          "--debug should be present, but --force-jumbo should not.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testDxCommandOptimizeNoJumbo() throws IOException {
    try (StepExecutionContext context = TestExecutionContext.newInstance()) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
      D8Step dx =
          new D8Step(filesystem, androidPlatformTarget, SAMPLE_OUTPUT_PATH, SAMPLE_FILES_TO_DEX);

      String expected =
          String.format(
              "d8 --output %s --release --lib %s %s",
              SAMPLE_OUTPUT_PATH,
              androidPlatformTarget.getAndroidJar(),
              Joiner.on(' ')
                  .join(
                      SAMPLE_FILES_TO_DEX.stream()
                          .map(filesystem::resolve)
                          .collect(Collectors.toList())));
      MoreAsserts.assertSteps(
          "Neither --debug nor --force-jumbo should be present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testDxCommandNoOptimizeForceJumbo() throws IOException {
    try (StepExecutionContext context = TestExecutionContext.newInstance()) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
      D8Step dx =
          new D8Step(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.of(D8Step.Option.NO_OPTIMIZE, D8Step.Option.FORCE_JUMBO),
              D8Step.D8);

      String expected =
          String.format(
              "d8 --output %s --debug --lib %s --force-jumbo %s",
              SAMPLE_OUTPUT_PATH,
              androidPlatformTarget.getAndroidJar(),
              Joiner.on(' ')
                  .join(
                      SAMPLE_FILES_TO_DEX.stream()
                          .map(filesystem::resolve)
                          .collect(Collectors.toList())));
      MoreAsserts.assertSteps(
          "Both --debug and --force-jumbo should be present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testMinSdkVersion() throws IOException {
    try (StepExecutionContext context = TestExecutionContext.newInstance()) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

      D8Step dx =
          new D8Step(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.noneOf(D8Step.Option.class),
              Optional.empty(),
              D8Step.D8,
              false,
              ImmutableSet.of(),
              Optional.empty(),
              Optional.of(28));

      String expected =
          String.format(
              "d8 --min-api 28 --output %s --release --lib %s %s",
              SAMPLE_OUTPUT_PATH,
              androidPlatformTarget.getAndroidJar(),
              Joiner.on(' ')
                  .join(
                      SAMPLE_FILES_TO_DEX.stream()
                          .map(filesystem::resolve)
                          .collect(Collectors.toList())));
      MoreAsserts.assertSteps(
          "Ensure that the --min-sdk-version flag is present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }

  @Test
  public void testMainDexList() throws IOException {
    if (Platform.detect() == Platform.WINDOWS) {
      return;
    }

    try (StepExecutionContext context = TestExecutionContext.newInstance()) {
      ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
      Path mainDexFilePath = Paths.get("/some/main/dex/file");

      D8Step dx =
          new D8Step(
              filesystem,
              androidPlatformTarget,
              SAMPLE_OUTPUT_PATH,
              SAMPLE_FILES_TO_DEX,
              EnumSet.noneOf(D8Step.Option.class),
              Optional.of(mainDexFilePath),
              D8Step.D8,
              false,
              ImmutableSet.of(),
              Optional.empty(),
              Optional.empty());

      String expected =
          String.format(
              "d8 --main-dex-list %s --output %s --release --lib %s %s",
              mainDexFilePath,
              SAMPLE_OUTPUT_PATH,
              androidPlatformTarget.getAndroidJar(),
              Joiner.on(' ')
                  .join(
                      SAMPLE_FILES_TO_DEX.stream()
                          .map(filesystem::resolve)
                          .collect(Collectors.toList())));
      MoreAsserts.assertSteps(
          "Ensure that the --main-dex-list flag is present.",
          ImmutableList.of(expected),
          ImmutableList.of(dx),
          context);
    }
  }
}
