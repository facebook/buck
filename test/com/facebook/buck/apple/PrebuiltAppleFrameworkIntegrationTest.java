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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltAppleFrameworkIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void testPrebuiltAppleFrameworkBuildsSomething() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_builds", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//prebuilt:BuckTest");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(workspace.getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testPrebuiltAppleFrameworkLinks() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//app:TestApp");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path testBinaryPath = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"));
    assertTrue(Files.exists(testBinaryPath));

    ProcessExecutor.Result otoolResult =
        workspace.runCommand("otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    assertThat(
        otoolResult.getStdout().orElse(""), containsString("@rpath/BuckTest.framework/BuckTest"));
    assertThat(otoolResult.getStdout().orElse(""), not(containsString("BuckTest.dylib")));
  }

  @Test
  public void testPrebuiltAppleFrameworkCopiedToBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//app:TestAppBundle#dwarf-and-dsym,include-frameworks");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path includedFramework =
        workspace
            .getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"))
            .resolve("TestAppBundle.app")
            .resolve("Frameworks")
            .resolve("BuckTest.framework");
    assertTrue(Files.isDirectory(includedFramework));
  }

  @Test
  public void testStaticWithDependencies() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_static", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//app:TestApp#static,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path testBinaryPath = workspace.getPath(BuildTargetPaths.getGenPath(filesystem, target, "%s"));

    ProcessExecutor.Result otoolResult =
        workspace.runCommand("otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    assertThat(otoolResult.getStdout().orElse(""), containsString("Foundation.framework"));
    assertThat(
        otoolResult.getStdout().orElse(""),
        not(containsString("@rpath/BuckTest.framework/BuckTest")));

    ProcessExecutor.Result nmResult = workspace.runCommand("nm", testBinaryPath.toString());
    assertEquals(0, nmResult.getExitCode());
    assertThat(nmResult.getStdout().orElse(""), containsString("S _OBJC_CLASS_$_Hello"));
    assertThat(nmResult.getStdout().orElse(""), not(containsString("U _OBJC_CLASS_$_Hello")));
    assertThat(nmResult.getStdout().orElse(""), containsString("S _OBJC_CLASS_$_Strings"));
    assertThat(nmResult.getStdout().orElse(""), not(containsString("U _OBJC_CLASS_$_Strings")));
  }

  @Test
  public void headerUsesShouldMapBackToTestApp() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "//app:TestApp#iphonesimulator-x86_64", "--config", "cxx.untracked_headers=error")
        .assertSuccess();
  }

  @Test
  public void ruleKeyChangesWhenFrameworkIsModified() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();

    String resultBefore;
    {
      ProcessResult result =
          workspace.runBuckCommand(
              "targets", "--show-rulekey", "//app:TestApp#iphonesimulator-x86_64");
      resultBefore = result.assertSuccess().getStdout();
    }

    workspace.writeContentsToPath("", "prebuilt/BuckTest.framework/Headers/Hello.h");

    String resultAfter;
    {
      ProcessResult result =
          workspace.runBuckCommand(
              "targets", "--show-rulekey", "//app:TestApp#iphonesimulator-x86_64");
      resultAfter = result.assertSuccess().getStdout();
    }

    assertNotEquals(
        "Rule Key before and after header change should be different", resultBefore, resultAfter);
  }

  @Test
  public void testProjectGeneratorGeneratesWorkingProject() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    workspace.runBuckCommand("project", "//app:workspace").assertSuccess();

    {
      ProcessExecutor.Result result =
          workspace.runCommand(
              "xcodebuild",

              // "json" output.
              "-json",

              // Make sure the output stays in the temp folder.
              "-derivedDataPath",
              "xcode-out/",

              // Build the project that we just generated
              "-workspace",
              "app/TestAppBundle.xcworkspace",
              "-scheme",
              "TestAppBundle",

              // Build for iphonesimulator
              "-arch",
              "x86_64",
              "-sdk",
              "iphonesimulator");
      result.getStderr().ifPresent(System.err::print);
      assertEquals("xcodebuild should succeed", 0, result.getExitCode());
    }

    Path appBundlePath =
        tmp.getRoot().resolve("xcode-out/Build/Products/Debug-iphonesimulator/TestAppBundle.app");
    assertTrue(
        "Framework is copied into bundle.",
        Files.isRegularFile(appBundlePath.resolve("Frameworks/BuckTest.framework/BuckTest")));

    {
      ProcessExecutor.Result result =
          workspace.runCommand("otool", "-l", appBundlePath.resolve("TestAppBundle").toString());
      assertThat(
          "App binary adds Framework dir to rpath.",
          result.getStdout().get(),
          matchesPattern(
              Pattern.compile(
                  ".*\\s+cmd LC_RPATH.*\\s+path @executable_path/Frameworks\\b.*",
                  Pattern.DOTALL)));
      assertThat(
          "App binary has load instruction for framework",
          result.getStdout().get(),
          matchesPattern(
              Pattern.compile(
                  ".*\\s+cmd LC_LOAD_DYLIB.*\\s+name @rpath/BuckTest.framework/BuckTest\\b.*",
                  Pattern.DOTALL)));
    }
  }

  @Test
  public void testProjectGeneratorGeneratesWorkingProjectWithoutTestHost() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    workspace.runBuckCommand("project", "//test:workspace").assertSuccess();

    {
      ProcessExecutor.Result result =
          workspace.runCommand(
              "xcodebuild",

              // "json" output.
              "-json",

              // Make sure the output stays in the temp folder.
              "-derivedDataPath",
              "xcode-out/",

              // Build the project that we just generated
              "-workspace",
              "test/testworkspace.xcworkspace",
              "-scheme",
              "testworkspace",
              "build-for-testing",

              // Build for iphonesimulator
              "-arch",
              "x86_64",
              "-sdk",
              "iphonesimulator");
      result.getStderr().ifPresent(System.err::print);
      assertEquals("xcodebuild should succeed", 0, result.getExitCode());
    }

    Path appBundlePath =
        tmp.getRoot().resolve("xcode-out/Build/Products/Debug-iphonesimulator/test.xctest");
    assertTrue(
        "Framework is copied into bundle.",
        Files.isRegularFile(appBundlePath.resolve("Frameworks/BuckTest.framework/BuckTest")));

    {
      ProcessExecutor.Result result =
          workspace.runCommand("otool", "-l", appBundlePath.resolve("test").toString());
      assertThat(
          "App binary adds Framework dir to rpath.",
          result.getStdout().get(),
          matchesPattern(
              Pattern.compile(
                  ".*\\s+cmd LC_RPATH.*\\s+path @loader_path/Frameworks\\b.*", Pattern.DOTALL)));
      assertThat(
          "App binary has load instruction for framework",
          result.getStdout().get(),
          matchesPattern(
              Pattern.compile(
                  ".*\\s+cmd LC_LOAD_DYLIB.*\\s+name @rpath/BuckTest.framework/BuckTest\\b.*",
                  Pattern.DOTALL)));
    }
  }

  @Test
  public void frameworkPathsPassedIntoSwiftc() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "prebuilt_apple_framework_static_swift", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:Foo#macosx-x86_64,static").assertSuccess();
  }
}
