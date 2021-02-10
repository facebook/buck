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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class CxxToolchainIntegrationTest {

  @Rule public TestLogSink testToolLogSink = new TestLogSink("FBCC_MAKE_LOG");

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testBuildWithCustomCxxToolchain() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);

    workspace.addBuckConfigLocalOption("cxx#good", "toolchain_target", "//toolchain:good");

    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:binary#good");

    assertEquals(
        String.format(
            "linker:%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "object: compile output: also not a real cpp%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));
  }

  @Test
  public void testDownwardLoggingIntegration() throws IOException {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);

    workspace.addBuckConfigLocalOption("cxx#good", "toolchain_target", "//toolchain:good");
    workspace.addBuckConfigLocalOption("python", "package_style", "inplace");
    workspace.addBuckConfigLocalOption("downward_api", "cxx_enabled", "true");
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("//:binary#good");
    processResult.assertSuccess();

    List<LogRecord> records = testToolLogSink.getRecords();
    LogRecord firstLogRecord = records.iterator().next();
    assertThat(firstLogRecord.getLevel(), equalTo(Level.WARNING));
    assertThat(firstLogRecord, TestLogSink.logRecordWithMessage(containsString("Hello from fbcc")));

    LogRecord secondLogRecord = Iterables.getLast(records);
    assertThat(secondLogRecord.getLevel(), equalTo(Level.WARNING));
    assertThat(
        secondLogRecord, TestLogSink.logRecordWithMessage(containsString("Hello again from fbcc")));
  }

  @Test
  public void testDepfilesWithCustomToolchain() throws IOException {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);

    workspace.addBuckConfigLocalOption("cxx#good", "toolchain_target", "//toolchain:good");
    workspace.addBuckConfigLocalOption("python", "package_style", "inplace");
    workspace.addBuckConfigLocalOption("build", "depfiles", "enabled");

    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:binary#good");

    assertEquals(
        String.format(
            "linker:%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "object: compile output: also not a real cpp%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));

    workspace.writeContentsToPath("data", "cxx.hpp");

    workspace.runBuckBuild("//:binary#good").assertSuccess();

    Map<BuildRuleSuccessType, AtomicInteger> successTypeCounter = new HashMap<>();

    BuckBuildLog buildLog = workspace.getBuildLog();
    for (BuildTarget allTarget : buildLog.getAllTargets()) {
      BuildRuleSuccessType successType =
          buildLog.getLogEntry(allTarget).getSuccessType().orElseThrow(IllegalStateException::new);
      successTypeCounter
          .computeIfAbsent(successType, ignored -> new AtomicInteger())
          .incrementAndGet();
    }
    // 1 preprocessor deps is built locally, 2 compilation rules are matching depfile
    assertEquals(1, successTypeCounter.get(BuildRuleSuccessType.BUILT_LOCALLY).get());
    assertEquals(2, successTypeCounter.get(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY).get());
  }

  @Test
  public void testBuildWithBadToolchainFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);

    workspace.addBuckConfigLocalOption("cxx#bad", "toolchain_target", "//toolchain:bad");

    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:binary#bad");
    result.assertFailure();
    assertThat(result.getStderr(), containsString("stderr: unimplemented"));
  }

  @Test
  public void testCxxToolchainWithCompilerMacro() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);
    workspace.addBuckConfigLocalOption(
        "cxx#good", "toolchain_target", "//toolchain:good-with-compiler-macro");
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:binary#good");
    assertEquals(
        String.format(
            "linker:%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "test arg: test file%n"
                + "%n"
                + "object: compile output: also not a real cpp%n"
                + "test arg: test file%n"
                + "%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));

    // Test that rule keys are computed correctly.
    Path testFilePath =
        workspace
            .getProjectFileSystem()
            .getRootPath()
            .resolve("tools")
            .resolve("test_file")
            .getPath();
    Files.write(testFilePath, Collections.singleton("modified file"));

    output = workspace.buildAndReturnOutput("//:binary#good");
    assertEquals(
        String.format(
            "linker:%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "test arg: modified file%n"
                + "%n"
                + "object: compile output: also not a real cpp%n"
                + "test arg: modified file%n"
                + "%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));
  }

  @Test
  public void testCxxToolchainWithLinkerMacro() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_toolchain", tmp);
    workspace.addBuckConfigLocalOption(
        "cxx#good", "toolchain_target", "//toolchain:good-with-linker-macro");
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:binary#good");
    assertEquals(
        String.format(
            "linker:%n"
                + "test arg: test file%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "object: compile output: also not a real cpp%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));

    // Test that rule keys are computed correctly.
    Path testFilePath =
        workspace
            .getProjectFileSystem()
            .getRootPath()
            .resolve("tools")
            .resolve("test_file")
            .getPath();
    Files.write(testFilePath, Collections.singleton("modified file"));

    output = workspace.buildAndReturnOutput("//:binary#good");
    assertEquals(
        String.format(
            "linker:%n"
                + "test arg: modified file%n"
                + "archive:%n"
                + "object: compile output: not a real cpp%n"
                + "object: compile output: also not a real cpp%n"
                + "ranlib applied.%n"),
        workspace.getFileContents(output));
  }
}
