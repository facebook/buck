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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class XctoolRunTestsStepTest {

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void xctoolCommandWithOnlyLogicTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/Foo.xctest"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }

  @Test
  public void xctoolCommandWhichFailsPrintsStderrToConsole() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/Foo.xctest"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolFailure = new FakeProcess(42, "", "Something went terribly wrong\n");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolFailure));
    TestConsole testConsole = new TestConsole();
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .setConsole(testConsole)
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(42));
    assertThat(
        testConsole.getTextWrittenToStdErr(),
        equalTo("xctool failed with exit code 42: Something went terribly wrong\n"));
  }

  @Test
  public void xctoolCommandWithOnlyAppTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.of("name=iPhone 5s"),
            ImmutableSet.of(),
            ImmutableMap.of(Paths.get("/path/to/FooAppTest.xctest"), Paths.get("/path/to/Foo.app")),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "name=iPhone 5s",
                    "run-tests",
                    "-appTest",
                    "/path/to/FooAppTest.xctest:/path/to/Foo.app"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }

  @Test
  public void xctoolCommandWithOnlyUITests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.of("name=iPhone 5s"),
            ImmutableSet.of(),
            ImmutableMap.of(),
            ImmutableMap.of(
                Paths.get("/path/to/FooAppTest.xctest"),
                ImmutableMap.of(Paths.get("/path/to/Foo.app"), Paths.get("/path/to/Target.app"))),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "name=iPhone 5s",
                    "run-tests",
                    "-uiTest",
                    "/path/to/FooAppTest.xctest:/path/to/Foo.app:/path/to/Target.app"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }

  @Test
  public void xctoolCommandWithAppAndLogicTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.of("name=iPhone 5s,OS=8.2"),
            ImmutableSet.of(Paths.get("/path/to/FooLogicTest.xctest")),
            ImmutableMap.of(Paths.get("/path/to/FooAppTest.xctest"), Paths.get("/path/to/Foo.app")),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "name=iPhone 5s,OS=8.2",
                    "run-tests",
                    "-logicTest",
                    "/path/to/FooLogicTest.xctest",
                    "-appTest",
                    "/path/to/FooAppTest.xctest:/path/to/Foo.app"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }

  @Test
  public void xctoolCommandWhichReturnsExitCode1DoesNotFailStep() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/Foo.xctest"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    // Test failure is indicated by xctool exiting with exit code 1, so it shouldn't
    // fail the step.
    FakeProcess fakeXctoolTestFailure = new FakeProcess(1, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolTestFailure));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }

  @Test
  public void xctoolCommandWhichReturnsExitCode400FailsStep() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/Foo.xctest"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolFailure = new FakeProcess(400, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolFailure));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), not(equalTo(0)));
  }

  @Test
  public void xctoolCommandWithTestSelectorFiltersTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(
                Paths.get("/path/to/FooTest.xctest"), Paths.get("/path/to/BarTest.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.builder().addRawSelectors("#.*Magic.*").build(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolListOnlyParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/FooTest.xctest",
                    "-logicTest",
                    "/path/to/BarTest.xctest",
                    "-listTestsOnly"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    try (InputStream stdout =
            getClass().getResourceAsStream("testdata/xctool-output/list-tests-only.json");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      assertThat(stdout, not(nullValue()));
      FakeProcess fakeXctoolListTestsProcess =
          new FakeProcess(0, ByteStreams.nullOutputStream(), stdout, stderr);
      ProcessExecutorParams xctoolRunTestsParamsWithOnlyFilters =
          ProcessExecutorParams.builder()
              .addCommand(
                  "/path/to/xctool",
                  "-reporter",
                  "json-stream",
                  "-sdk",
                  "iphonesimulator",
                  "run-tests",
                  "-logicTest",
                  "/path/to/FooTest.xctest",
                  "-logicTest",
                  "/path/to/BarTest.xctest",
                  "-only",
                  "/path/to/FooTest.xctest:FooTest/testMagicValue,FooTest/testAnotherMagicValue",
                  "-only",
                  "/path/to/BarTest.xctest:BarTest/testYetAnotherMagicValue")
              .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
              .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
              .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
              .build();
      FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
      FakeProcessExecutor processExecutor =
          new FakeProcessExecutor(
              ImmutableMap.of(
                  xctoolListOnlyParams, fakeXctoolListTestsProcess,
                  // The important part of this test is that we want to make sure xctool
                  // is run with the correct -only parameters. (We don't really care what
                  // the return value of this xctool is, so we make it always succeed.)
                  xctoolRunTestsParamsWithOnlyFilters, fakeXctoolSuccess));
      ExecutionContext executionContext =
          TestExecutionContext.newBuilder()
              .setProcessExecutor(processExecutor)
              .setEnvironment(ImmutableMap.of())
              .build();
      assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
    }
  }

  @Test
  public void xctoolCommandWithTestSelectorFailsIfListTestsOnlyFails() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(
                Paths.get("/path/to/FooTest.xctest"), Paths.get("/path/to/BarTest.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.builder().addRawSelectors("#.*Magic.*").build(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    ProcessExecutorParams xctoolListOnlyParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/FooTest.xctest",
                    "-logicTest",
                    "/path/to/BarTest.xctest",
                    "-listTestsOnly"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolListTestsFailureProcess =
        new FakeProcess(42, "", "Chopper Dave, we have Uh-Oh");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(
            ImmutableMap.of(xctoolListOnlyParams, fakeXctoolListTestsFailureProcess));
    TestConsole testConsole = new TestConsole();
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .setConsole(testConsole)
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(42));
    assertThat(
        testConsole.getTextWrittenToStdErr(),
        allOf(
            containsString("xctool failed with exit code 42: Chopper Dave, we have Uh-Oh"),
            containsString("Failed to query tests with xctool")));
  }

  @Test
  public void xctoolCommandWithTestSelectorMatchingNothingDoesNotFail() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of(),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(
                Paths.get("/path/to/FooTest.xctest"), Paths.get("/path/to/BarTest.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.builder().addRawSelectors("Blargh#Xyzzy").build(),
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    ProcessExecutorParams xctoolListOnlyParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/FooTest.xctest",
                    "-logicTest",
                    "/path/to/BarTest.xctest",
                    "-listTestsOnly"))
            .setEnvironment(ImmutableMap.of("DEVELOPER_DIR", "/path/to/developer/dir"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    try (InputStream stdout =
            getClass().getResourceAsStream("testdata/xctool-output/list-tests-only.json");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      assertThat(stdout, not(nullValue()));
      FakeProcess fakeXctoolListTestsProcess =
          new FakeProcess(0, ByteStreams.nullOutputStream(), stdout, stderr);
      FakeProcessExecutor processExecutor =
          new FakeProcessExecutor(
              ImmutableMap.of(xctoolListOnlyParams, fakeXctoolListTestsProcess));
      ExecutionContext executionContext =
          TestExecutionContext.newBuilder()
              .setProcessExecutor(processExecutor)
              .setEnvironment(ImmutableMap.of())
              .build();
      assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
    }
  }

  @Test
  public void testDirectoryAndLevelPassedInEnvironment() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step =
        new XctoolRunTestsStep(
            projectFilesystem,
            Paths.get("/path/to/xctool"),
            ImmutableMap.of("LLVM_PROFILE_FILE", "/tmp/some.profraw"),
            Optional.empty(),
            "iphonesimulator",
            Optional.empty(),
            ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
            ImmutableMap.of(),
            ImmutableMap.of(),
            Paths.get("/path/to/output.json"),
            Optional.empty(),
            AppleDeveloperDirectoryForTestsProvider.of(Paths.get("/path/to/developer/dir")),
            TestSelectorList.EMPTY,
            false,
            Optional.of("TEST_LOG_PATH"),
            Optional.of(Paths.get("/path/to/test-logs")),
            Optional.of("TEST_LOG_LEVEL"),
            Optional.of("verbose"),
            Optional.empty(),
            Optional.of("/path/to/snapshotimages"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream",
                    "-sdk",
                    "iphonesimulator",
                    "run-tests",
                    "-logicTest",
                    "/path/to/Foo.xctest"))
            // This is the important part of this test: only if the process is executed
            // with a matching environment will the test pass.
            .setEnvironment(
                ImmutableMap.of(
                    "DEVELOPER_DIR", "/path/to/developer/dir",
                    "XCTOOL_TEST_ENV_TEST_LOG_PATH", "/path/to/test-logs",
                    "XCTOOL_TEST_ENV_TEST_LOG_LEVEL", "verbose",
                    "XCTOOL_TEST_ENV_FB_REFERENCE_IMAGE_DIR", "/path/to/snapshotimages",
                    "LLVM_PROFILE_FILE", "/tmp/some.profraw"))
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor =
        new FakeProcessExecutor(ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProcessExecutor(processExecutor)
            .setEnvironment(ImmutableMap.of())
            .build();
    assertThat(step.execute(executionContext).getExitCode(), equalTo(0));
  }
}
