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

package com.facebook.buck.cli;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assume.assumeFalse;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.hamcrest.Matchers.is;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore("Disabled due to timeouts installing into the simulator")
public class InstallCommandIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void appleBundleInstallsInIphoneSimulator() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "install",
        "//:DemoApp");

    assumeFalse(result.getStderr().contains("no appropriate simulator found"));
    result.assertSuccess();

    // TODO(bhamiltoncx): If we make the install command output the UDID of the
    // simulator, we could poke around in
    // ~/Library/Developer/CoreSimulator/[UDID] to see if the bits were installed.
  }

  @Test
  public void appleBundleInstallsAndRunsInIphoneSimulator() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "install",
        "-r",
        "//:DemoApp");

    assumeFalse(result.getStderr().contains("no appropriate simulator found"));
    result.assertSuccess();
  }

  @Test
  public void appleBundleInstallsAndRunsInIphoneSimulatorWithDwarfDebugging()
      throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // build locally
    ProcessResult result = workspace.runBuckCommand(
        "install",
        "--config",
        "apple.default_debug_info_format_for_binaries=DWARF",
        "--config",
        "apple.default_debug_info_format_for_libraries=DWARF",
        "--config",
        "apple.default_debug_info_format_for_tests=DWARF",
        "-r",
        "//:DemoApp");

    assumeFalse(result.getStderr().contains("no appropriate simulator found"));
    result.assertSuccess();

    // find port to connect lldb to
    Pattern p = Pattern.compile("lldb -p \\d{1,6}");  // "lldb -p 12345"
    Matcher matcher = p.matcher(result.getStderr());
    assertThat(matcher.find(), equalTo(true));
    String[] lldbCommand = matcher.group().split(" ");

    ProcessExecutor executor = new ProcessExecutor(new TestConsole());

    // run lldb session
    ProcessExecutor.Result lldbResult = executor.launchAndExecute(
        ProcessExecutorParams.builder().addCommand(lldbCommand).build(),
        ImmutableSet.<ProcessExecutor.Option>of(),
        Optional.of("b application:didFinishLaunchingWithOptions:\nb\nexit\nY\n"),
        Optional.<Long>absent(),
        Optional.<Function<Process, Void>>absent());
    assertThat(lldbResult.getExitCode(), equalTo(0));

    // check that lldb resolved breakpoint locations
    String lldbOutput = lldbResult.getStdout().or("");
    assertThat(lldbOutput, containsString("Current breakpoints:"));
    assertThat(
        lldbOutput,
        containsString("name = 'application:didFinishLaunchingWithOptions:', " +
            "locations = 1, resolved = 1, hit count = 0"));

    // clean buck out
    workspace.runBuckCommand("clean");
    // build again - get everything from cache now
    result = workspace.runBuckCommand(
        "install",
        "--config",
        "apple.default_debug_info_format_for_binaries=DWARF",
        "--config",
        "apple.default_debug_info_format_for_libraries=DWARF",
        "--config",
        "apple.default_debug_info_format_for_tests=DWARF",
        "-r",
        "//:DemoApp");
    result.assertSuccess();

    matcher = p.matcher(result.getStderr());
    assertThat(matcher.find(), equalTo(true));
    String[] lldbCommand2 = matcher.group().split(" ");

    // run lldb session again - now on top of files fetched from cache
    lldbResult = executor.launchAndExecute(
        ProcessExecutorParams.builder().addCommand(lldbCommand2).build(),
        ImmutableSet.<ProcessExecutor.Option>of(),
        Optional.of("b application:didFinishLaunchingWithOptions:\nb\nexit\nY\n"),
        Optional.<Long>absent(),
        Optional.<Function<Process, Void>>absent());
    assertThat(lldbResult.getExitCode(), equalTo(0));

    // check that lldb resolved breakpoint locations with files from cache
    lldbOutput = lldbResult.getStdout().or("");
    assertThat(lldbOutput, containsString("Current breakpoints:"));
    assertThat(
        lldbOutput,
        containsString("name = 'application:didFinishLaunchingWithOptions:', " +
            "locations = 1, resolved = 1, hit count = 0"));
  }

  @Test
  public void appleBundleInstallsInDeviceWithHelperAsPath() throws IOException,
      InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(FakeAppleDeveloperEnvironment.supportsBuildAndInstallToDevice());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle", tmp);
    workspace.setUp();

    assumeTrue(FakeAppleDeveloperEnvironment.hasDeviceCurrentlyConnected(workspace.getPath(
                "iOSConsole/iOSConsole"
            )));


    ProcessResult result = workspace.runBuckCommand(
        "install",
        "//:DemoApp#iphoneos-arm64");
    result.assertSuccess();
  }

  @Test
  public void appleBundleInstallsInDeviceWithHelperAsTarget() throws IOException,
      InterruptedException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    assumeTrue(FakeAppleDeveloperEnvironment.supportsBuildAndInstallToDevice());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_app_bundle_with_device_helper_as_target", tmp);
    workspace.setUp();

    assumeTrue(FakeAppleDeveloperEnvironment.hasDeviceCurrentlyConnected(workspace.getPath(
                "iOSConsole/iOSConsole"
            )));


    ProcessResult result = workspace.runBuckCommand(
        "install",
        "//:DemoApp#iphoneos-arm64");
    result.assertSuccess();
  }
}
