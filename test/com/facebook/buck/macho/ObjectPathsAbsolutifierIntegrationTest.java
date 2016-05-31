/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.macho;


import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.DebugPathSanitizer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ObjectPathsAbsolutifierIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testAbsolutifyingPathsForIntel64Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-x86_64");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForIntel32Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphonesimulator-i386");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForArm64Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphoneos-arm64");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testAbsolutifyingPathsForArm32Bit() throws IOException, InterruptedException {
    Flavor platformFlavor = ImmutableFlavor.of("iphoneos-armv7");
    runAndCheckAbsolutificationWithPlatformFlavor(platformFlavor);
  }

  private void runAndCheckAbsolutificationWithPlatformFlavor(
      Flavor platformFlavor) throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
        .withAppendedFlavors(platformFlavor);
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand(
            "build",
            "--config",
            "cxx.cflags=-g",
            target.getFullyQualifiedName());
    result.assertSuccess();

    Path relativeSanitizedObjectFilePath = BuildTargets
        .getGenPath(
            filesystem,
            target.withFlavors(
                platformFlavor,
                ImmutableFlavor.of("compile-" + sanitize("main.c.o"))),
            "%s")
        .resolve("main.c.o");

    Path relativeSanitizedSourceFilePath = BuildTargets
        .getGenPath(
            filesystem,
            target.withFlavors(
                platformFlavor,
                ImmutableFlavor.of("preprocess-" + sanitize("main.c.i"))),
            "%s");

    Path sanitizedBinaryPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                target.withFlavors(platformFlavor),
                "%s"));
    Path unsanizitedBinaryPath = workspace.getPath(
        filesystem.getBuckPaths().getScratchDir().resolve(sanitizedBinaryPath.getFileName()));

    // this was stolen from the implementation detail of AppleCxxPlatforms
    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        250,
        File.separatorChar,
        Paths.get("."),
        ImmutableBiMap.<Path, Path>of());

    String oldCompDirValue = sanitizer.getCompilationDirectory();
    String newCompDirValue = workspace.getDestPath().toString();

    result = workspace.runBuckCommand(
        "machoutils",
        "absolutify_object_paths",
        "--binary",
        sanitizedBinaryPath.toString(),
        "--output",
        unsanizitedBinaryPath.toString(),
        "--old_compdir",
        oldCompDirValue,
        "--new_compdir",
        newCompDirValue);
    result.assertSuccess();

    ProcessExecutor.Result sanitizedResult =
        workspace.runCommand("nm", "-a", sanitizedBinaryPath.toString());
    ProcessExecutor.Result unsanitizedResult =
        workspace.runCommand("nm", "-a", unsanizitedBinaryPath.toString());

    String sanitizedOutput = sanitizedResult.getStdout().or("");
    String unsanitizedOutput = unsanitizedResult.getStdout().or("");

    // check that weird buck comp dir is not present anymore
    assertThat(
        sanitizedOutput,
        containsString("SO .///////////////////////////////////////////////////////////////"));
    assertThat(
        unsanitizedOutput,
        not(containsString("SO .///////////////////////////////////////////////////////////////")));

    // check that relative path to object file is not present anymore
    assertThat(sanitizedOutput, containsString("OSO ./buck-out"));
    assertThat(unsanitizedOutput, not(containsString("OSO ./buck-out")));

    // check that relative path to source file is not present anymore
    assertThat(sanitizedOutput, containsString("SOL Apps/TestApp/main.c"));
    assertThat(unsanitizedOutput, not(containsString("SOL Apps/TestApp/main.c")));

    // check that absolute path to included source file is correct
    assertThat(
        unsanitizedOutput,
        containsString("SOL " + newCompDirValue + "/Apps/TestApp/main.c"));
    // check that absolute path to object file is correct
    assertThat(
        unsanitizedOutput,
        containsString("OSO " + newCompDirValue + "/buck-out/bin/" +
            relativeSanitizedObjectFilePath.toString()));
    // check that absolute path to source file is correct
    assertThat(
        unsanitizedOutput,
        containsString("SO " + newCompDirValue + "/" + relativeSanitizedSourceFilePath.toString()));
  }
}
