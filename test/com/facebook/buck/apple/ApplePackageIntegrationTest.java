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

package com.facebook.buck.apple;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public class ApplePackageIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void packageHasProperStructure() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();
    workspace.enableDirCache();

    workspace.runBuckCommand("build", "//:DemoApp").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:DemoApp#no-debug,no-include-frameworks");

    workspace.runBuckCommand("clean").assertSuccess();

    workspace.runBuckCommand("build", "//:DemoAppPackage").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache(
        "//:DemoApp#no-debug,no-include-frameworks");
    workspace.getBuildLog().assertTargetBuiltLocally("//:DemoAppPackage");

    Path templateDir = TestDataHelper.getTestDataScenario(
        this,
        "simple_application_bundle_no_debug");

    ZipInspector zipInspector = new ZipInspector(
        workspace.getPath("buck-out/gen/DemoAppPackage.ipa"));
    zipInspector.assertFileExists(("Payload/DemoApp.app/DemoApp"));
    zipInspector.assertFileContents("Payload/DemoApp.app/PkgInfo", new String(Files.readAllBytes(
            templateDir.resolve(
                "buck-out/gen/DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks" +
                    "/DemoApp.app/PkgInfo.expected")),
            UTF_8));
  }

  @Test
  public void packageHasProperStructureForWatch() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "watch_application_bundle",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoAppPackage").assertSuccess();

    Path destination = workspace.getDestPath();
    Unzip.extractZipFile(
        workspace.getPath("buck-out/gen/DemoAppPackage.ipa"),
        destination,
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    assertTrue(Files.exists(destination.resolve(
        "Payload/DemoApp.app/Watch/DemoWatchApp.app/_WatchKitStub/WK")));
  }

  @Test
  public void packageSupportsFatBinaries() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand(
        "build",
        "//:DemoAppPackage#iphonesimulator-i386,iphonesimulator-x86_64")
        .assertSuccess();

    Unzip.extractZipFile(
        workspace.getPath(
            "buck-out/gen/DemoAppPackage#iphonesimulator-i386,iphonesimulator-x86_64.ipa"),
        workspace.getDestPath(),
        Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    ProcessExecutor executor = new ProcessExecutor(new TestConsole());

    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "lipo",
                    "-info",
                    workspace.getDestPath().resolve("Payload/DemoApp.app/DemoApp").toString()))
            .build();

    // Specify that stdout is expected, or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options =
        EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT, ProcessExecutor.Option.IS_SILENT);

    ProcessExecutor.Result result = executor.launchAndExecute(
        processExecutorParams,
        options,
        /* stdin */ Optional.<String>absent(),
        /* timeOutMs */ Optional.<Long>absent(),
        /* timeOutHandler */ Optional.<Function<Process, Void>>absent());

    assertEquals(result.getExitCode(), 0);
    assertTrue(result.getStdout().isPresent());
    String output = result.getStdout().get();
    assertTrue(output.contains("i386"));
    assertTrue(output.contains("x86_64"));
  }
}
