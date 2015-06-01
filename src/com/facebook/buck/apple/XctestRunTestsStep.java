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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs {@code xctest} on logic or application tests paired with a host.
 *
 * The output is written as standard XCTest output to {@code outputPath}
 * and can be parsed by {@link XctestOutputParsing}.
 */
public class XctestRunTestsStep implements Step {

  private static final Logger LOG = Logger.get(XctestRunTestsStep.class);

  private final ImmutableList<String> xctest;
  private final String testArgument; // -XCTest or -SenTest
  private final Path logicTestBundlePath;
  private final Path outputPath;

  public XctestRunTestsStep(
      ImmutableList<String> xctest,
      String testArgument,
      Path logicTestBundlePath,
      Path outputPath) {
    this.xctest = xctest;
    this.testArgument = testArgument;
    this.logicTestBundlePath = logicTestBundlePath;
    this.outputPath = outputPath;
  }

  @Override
  public String getShortName() {
    return "xctest-run-tests";
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(xctest);
    args.add(testArgument);
    args.add("All");
    args.add(logicTestBundlePath.toString());

    ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
    environment.putAll(context.getEnvironment());
    // if (appTestHostAppPath.isPresent()) {
    //   TODO(grp): Pass XCBundleInjection environment.
    // }

    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setCommand(args.build());
    builder.setEnvironment(environment.build());
    builder.setRedirectError(
        ProcessBuilder.Redirect.to(
            filesystem.resolve(outputPath).toFile()));

    int exitCode;
    try {
      ProcessExecutor executor = context.getProcessExecutor();
      ProcessExecutor.Result result = executor.launchAndExecute(
          builder.build(),
          ImmutableSet.<ProcessExecutor.Option>of(),
          Optional.<String>absent(),
          Optional.<Long>absent());
      LOG.debug("xctest exit code: %d", result.getExitCode());

      // NOTE(grp): xctest always seems to have an exit code of 1.
      // Failure will be detected through the output, not from here.
      exitCode = 0;
    } catch (IOException e) {
      LOG.error(e, "Error while running %s", args.build());
      e.printStackTrace(context.getStdErr());
      exitCode = 1;
    }

    return exitCode;
  }

  @Override
  public final String getDescription(ExecutionContext context) {
    return "Run XCTest or SenTestingKit tests";
  }
}
