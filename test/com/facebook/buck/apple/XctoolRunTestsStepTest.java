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

import static org.junit.Assert.assertThat;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class XctoolRunTestsStepTest {

  @Test
  public void xctoolCommandWithOnlyLogicTests() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    XctoolRunTestsStep step = new XctoolRunTestsStep(
        Paths.get("/path/to/xctool"),
        "x86_64",
        "iphonesimulator",
        "iPhone 5s",
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream:/path/to/output.json",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "arch=x86_64,name=iPhone 5s",
                    "-logicTest",
                    "/path/to/Foo.xctest",
                    "run-tests"))
            // This mimics the hard-coded behavior of ShellStep.
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setEnvironment(ImmutableMap.<String, String>of())
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setProjectFilesystem(projectFilesystem)
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
        Paths.get("/path/to/xctool"),
        "x86_64",
        "iphonesimulator",
        "iPhone 5s",
        ImmutableSet.<Path>of(),
        ImmutableMap.of(
            Paths.get("/path/to/FooAppTest.xctest"),
            Paths.get("/path/to/Foo.app")),
        Paths.get("/path/to/output.json"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream:/path/to/output.json",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "arch=x86_64,name=iPhone 5s",
                    "-appTest",
                    "/path/to/FooAppTest.xctest:/path/to/Foo.app",
                    "run-tests"))
            // This mimics the hard-coded behavior of ShellStep.
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setEnvironment(ImmutableMap.<String, String>of())
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setProjectFilesystem(projectFilesystem)
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
        Paths.get("/path/to/xctool"),
        "x86_64",
        "iphonesimulator",
        "iPhone 5s",
        ImmutableSet.of(
            Paths.get("/path/to/FooLogicTest.xctest")),
        ImmutableMap.of(
            Paths.get("/path/to/FooAppTest.xctest"),
            Paths.get("/path/to/Foo.app")),
        Paths.get("/path/to/output.json"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream:/path/to/output.json",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "arch=x86_64,name=iPhone 5s",
                    "-logicTest",
                    "/path/to/FooLogicTest.xctest",
                    "-appTest",
                    "/path/to/FooAppTest.xctest:/path/to/Foo.app",
                    "run-tests"))
            // This mimics the hard-coded behavior of ShellStep.
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setEnvironment(ImmutableMap.<String, String>of())
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(0, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setProjectFilesystem(projectFilesystem)
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
        Paths.get("/path/to/xctool"),
        "x86_64",
        "iphonesimulator",
        "iPhone 5s",
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream:/path/to/output.json",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "arch=x86_64,name=iPhone 5s",
                    "-logicTest",
                    "/path/to/Foo.xctest",
                    "run-tests"))
            // This mimics the hard-coded behavior of ShellStep.
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setEnvironment(ImmutableMap.<String, String>of())
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(1, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setProjectFilesystem(projectFilesystem)
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
        Paths.get("/path/to/xctool"),
        "x86_64",
        "iphonesimulator",
        "iPhone 5s",
        ImmutableSet.of(Paths.get("/path/to/Foo.xctest")),
        ImmutableMap.<Path, Path>of(),
        Paths.get("/path/to/output.json"));
    ProcessExecutorParams xctoolParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "/path/to/xctool",
                    "-reporter",
                    "json-stream:/path/to/output.json",
                    "-sdk",
                    "iphonesimulator",
                    "-destination",
                    "arch=x86_64,name=iPhone 5s",
                    "-logicTest",
                    "/path/to/Foo.xctest",
                    "run-tests"))
            // This mimics the hard-coded behavior of ShellStep.
            .setDirectory(projectFilesystem.getRootPath().toAbsolutePath().toFile())
            .setEnvironment(ImmutableMap.<String, String>of())
            .build();
    FakeProcess fakeXctoolSuccess = new FakeProcess(400, "", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xctoolParams, fakeXctoolSuccess));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setProcessExecutor(processExecutor)
        .setProjectFilesystem(projectFilesystem)
        .setEnvironment(ImmutableMap.<String, String>of())
        .build();
    assertThat(
        step.execute(executionContext),
        not(equalTo(0)));
  }
}
