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

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.MungingDebugPathSanitizer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableBiMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CompDirReplacerIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void testCompDirReplacerForIntel64Bit() throws Exception {
    Flavor platformFlavor = InternalFlavor.of("iphonesimulator-x86_64");
    runCompDirReplacerWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testCompDirReplacerForIntel32Bit() throws Exception {
    Flavor platformFlavor = InternalFlavor.of("iphonesimulator-i386");
    runCompDirReplacerWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testCompDirReplacerForArm64Bit() throws Exception {
    Flavor platformFlavor = InternalFlavor.of("iphoneos-arm64");
    runCompDirReplacerWithPlatformFlavor(platformFlavor);
  }

  @Test
  public void testCompDirReplacerForArm32Bit() throws Exception {
    Flavor platformFlavor = InternalFlavor.of("iphoneos-armv7");
    runCompDirReplacerWithPlatformFlavor(platformFlavor);
  }

  private void runCompDirReplacerWithPlatformFlavor(Flavor platformFlavor)
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(platformFlavor);
    ProcessResult result =
        workspace.runBuckCommand(
            "build", "--config", "cxx.cflags=-g", target.getFullyQualifiedName());
    result.assertSuccess();

    Path sanitizedObjectFilePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem,
                    target.withFlavors(
                        platformFlavor, InternalFlavor.of("compile-" + sanitize("main.c.o"))),
                    "%s")
                .resolve("main.c.o"));
    Path unsanizitedObjectFilePath =
        workspace.getPath(
            filesystem.getBuckPaths().getScratchDir().resolve(Paths.get("unsanitized.main.c.o")));

    // this was stolen from the implementation detail of AppleCxxPlatforms
    DebugPathSanitizer sanitizer =
        new MungingDebugPathSanitizer(250, File.separatorChar, Paths.get("."), ImmutableBiMap.of());

    String oldCompDirValue = sanitizer.getCompilationDirectory();
    String newCompDirValue = workspace.getDestPath().toString();

    result =
        workspace.runBuckCommand(
            "machoutils",
            "fix_compdir",
            "--binary",
            sanitizedObjectFilePath.toString(),
            "--output",
            unsanizitedObjectFilePath.toString(),
            "--old_compdir",
            oldCompDirValue,
            "--new_compdir",
            newCompDirValue);
    result.assertSuccess();

    ProcessExecutor.Result sanitizedResult =
        workspace.runCommand("dwarfdump", sanitizedObjectFilePath.toString());
    ProcessExecutor.Result unsanitizedResult =
        workspace.runCommand("dwarfdump", unsanizitedObjectFilePath.toString());

    assertThat(
        sanitizedResult.getStdout().orElse(""),
        containsString("AT_comp_dir( \"./////////////////////////////"));
    assertThat(
        unsanitizedResult.getStdout().orElse(""),
        not(containsString("AT_comp_dir( \"./////////////////////////////")));
    assertThat(
        unsanitizedResult.getStdout().orElse(""),
        containsString("AT_comp_dir( \"" + newCompDirValue));
  }
}
