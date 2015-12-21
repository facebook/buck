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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CxxPrepareForLinkStepTest {
  @Test
  public void cxxLinkStepPassesLinkerOptionsViaArgFile() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path argFilePath = projectFilesystem.getRootPath().resolve(
        "/tmp/cxxLinkStepPassesLinkerOptionsViaArgFile.txt");
    Path output = projectFilesystem.getRootPath().resolve("output");

    runTestForArgFilePathAndOutputPath(argFilePath, output);
  }

  @Test
  public void cxxLinkStepCreatesDirectoriesIfNeeded() throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path argFilePath = projectFilesystem.getRootPath().resolve(
        "/tmp/unexisting_parent_folder/argfile.txt");
    Path output = projectFilesystem.getRootPath().resolve("output");

    Files.deleteIfExists(argFilePath);
    Files.deleteIfExists(argFilePath.getParent());

    runTestForArgFilePathAndOutputPath(argFilePath, output);

    // cleanup after test
    Files.deleteIfExists(argFilePath);
    Files.deleteIfExists(argFilePath.getParent());
  }

  private void runTestForArgFilePathAndOutputPath(
      Path argFilePath,
      Path output) throws IOException, InterruptedException {
    ExecutionContext context = TestExecutionContext.newInstance();

    // Setup some dummy values for inputs to the CxxLinkStep
    ImmutableList<String> args = ImmutableList.of(
        "-rpath",
        "hello",
        "a.o",
        "libb.a",
        "-lsysroot",
        "/Library/Application Support/blabla",
        "-F/System/Frameworks",
        "-L/System/libraries",
        "-lz");

    // Create our CxxLinkStep to test.
    CxxPrepareForLinkStep step = new CxxPrepareForLinkStep(
        argFilePath,
        output,
        args);

    step.execute(context);

    assertThat(Files.exists(argFilePath), Matchers.equalTo(true));

    ImmutableList<String> expectedFileContents = ImmutableList.<String>builder()
        .add("-o", output.toString())
        .add("-rpath")
        .add("hello")
        .add("a.o")
        .add("libb.a")
        .add("-lsysroot")
        .add("\"/Library/Application Support/blabla\"")
        .add("-F/System/Frameworks")
        .add("-L/System/libraries")
        .add("-lz")
        .build();
    List<String> fileContents = Files.readAllLines(argFilePath, StandardCharsets.UTF_8);
    assertThat(fileContents, Matchers.<List<String>>equalTo(expectedFileContents));
    Files.deleteIfExists(argFilePath);
  }


}
