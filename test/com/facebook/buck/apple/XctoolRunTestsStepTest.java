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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class XctoolRunTestsStepTest {

  @Test
  public void xctoolCommandWithOnlyLogicTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        projectFilesystem,
        Paths.get("/path/to/xctool"),
        Optional.<Long>absent(),
        "iphonesimulator",
        Optional.<String>absent(),
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"),
        Optional.<XctoolRunTestsStep.StdoutReadingCallback>absent());
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
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        equalTo(0));
  }

  @Test
  public void xctoolCommandWithOnlyAppTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        projectFilesystem,
        Paths.get("/path/to/xctool"),
        Optional.<Long>absent(),
        "iphonesimulator",
        Optional.of("name=iPhone 5s"),
        ImmutableSet.<Path>of(),
        ImmutableMap.of(
            Paths.get("/path/to/FooAppTest.xctest"),
            Paths.get("/path/to/Foo.app")),
        Paths.get("/path/to/output.json"),
        Optional.<XctoolRunTestsStep.StdoutReadingCallback>absent());
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
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        equalTo(0));
  }

  @Test
  public void xctoolCommandWithAppAndLogicTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        projectFilesystem,
        Paths.get("/path/to/xctool"),
        Optional.<Long>absent(),
        "iphonesimulator",
        Optional.of("name=iPhone 5s,OS=8.2"),
        ImmutableSet.of(
            Paths.get("/path/to/FooLogicTest.xctest")),
        ImmutableMap.of(
            Paths.get("/path/to/FooAppTest.xctest"),
            Paths.get("/path/to/Foo.app")),
        Paths.get("/path/to/output.json"),
        Optional.<XctoolRunTestsStep.StdoutReadingCallback>absent());
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
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        equalTo(0));
  }

  @Test
  public void xctoolCommandWhichReturnsExitCode1DoesNotFailStep() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        projectFilesystem,
        Paths.get("/path/to/xctool"),
        Optional.<Long>absent(),
        "iphonesimulator",
        Optional.<String>absent(),
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"),
        Optional.<XctoolRunTestsStep.StdoutReadingCallback>absent());
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
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(1, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        equalTo(0));
  }

  @Test
  public void xctoolCommandWhichReturnsExitCode400FailsStep() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        projectFilesystem,
        Paths.get("/path/to/xctool"),
        Optional.<Long>absent(),
        "iphonesimulator",
        Optional.<String>absent(),
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"),
        Optional.<XctoolRunTestsStep.StdoutReadingCallback>absent());
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
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(400, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        not(equalTo(0)));
  }
}
