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

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.TeeInputStream;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs {@code xctest} on logic or application tests paired with a host.
 *
 * <p>The output is written as standard XCTest output to {@code outputPath} and can be parsed by
 * {@link XctestOutputParsing}.
 */
class XctestRunTestsStep implements Step {

  private static final Logger LOG = Logger.get(XctestRunTestsStep.class);

  public interface OutputReadingCallback {
    void readOutput(InputStream output) throws IOException;
  }

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> xctest;
  private final Path logicTestBundlePath;
  private final Path outputPath;
  private final Optional<? extends OutputReadingCallback> outputReadingCallback;
  private final AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider;

  public XctestRunTestsStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment,
      ImmutableList<String> xctest,
      Path logicTestBundlePath,
      Path outputPath,
      Optional<? extends OutputReadingCallback> outputReadingCallback,
      AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider) {
    this.filesystem = filesystem;
    this.environment = environment;
    this.xctest = xctest;
    this.logicTestBundlePath = logicTestBundlePath;
    this.outputPath = outputPath;
    this.outputReadingCallback = outputReadingCallback;
    this.appleDeveloperDirectoryForTestsProvider = appleDeveloperDirectoryForTestsProvider;
  }

  @Override
  public String getShortName() {
    return "xctest-run-tests";
  }

  public ImmutableList<String> getCommand() {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(xctest);
    args.add("-XCTest");
    args.add("All");
    args.add(logicTestBundlePath.toString());
    return args.build();
  }

  public ImmutableMap<String, String> getEnv(ExecutionContext context) {
    Map<String, String> environment = new HashMap<>(context.getEnvironment());
    Path xcodeDeveloperDir =
        appleDeveloperDirectoryForTestsProvider.getAppleDeveloperDirectoryForTests();
    environment.put("DEVELOPER_DIR", xcodeDeveloperDir.toString());
    environment.putAll(this.environment);
    // if (appTestHostAppPath.isPresent()) {
    //   TODO(grp): Pass XCBundleInjection environment.
    // }
    return ImmutableMap.copyOf(environment);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutorParams.Builder builder =
        ProcessExecutorParams.builder()
            .addAllCommand(getCommand())
            .setDirectory(filesystem.getRootPath().toAbsolutePath())
            .setRedirectErrorStream(true)
            .setEnvironment(getEnv(context));

    ProcessExecutorParams params = builder.build();
    LOG.debug("xctest command: %s", Joiner.on(" ").join(params.getCommand()));

    ProcessExecutor executor = context.getProcessExecutor();
    ProcessExecutor.LaunchedProcess launchedProcess = executor.launchProcess(params);

    int exitCode;
    try (OutputStream outputStream = filesystem.newFileOutputStream(outputPath);
        TeeInputStream outputWrapperStream =
            new TeeInputStream(launchedProcess.getInputStream(), outputStream)) {
      if (outputReadingCallback.isPresent()) {
        // The caller is responsible for reading all the data, which TeeInputStream will
        // copy to outputStream.
        outputReadingCallback.get().readOutput(outputWrapperStream);
      } else {
        // Nobody's going to read from outputWrapperStream, so close it and copy
        // the process's stdout and stderr to outputPath directly.
        outputWrapperStream.close();
        ByteStreams.copy(launchedProcess.getInputStream(), outputStream);
      }
      exitCode = executor.waitForLaunchedProcess(launchedProcess).getExitCode();

      // There's no way to distinguish a test failure from an xctest issue. We don't
      // want to fail the step on a test failure, so return 0 for any xctest exit code.
      exitCode = 0;

      LOG.debug("Finished running command, exit code %d", exitCode);
    } finally {
      context.getProcessExecutor().destroyLaunchedProcess(launchedProcess);
      context.getProcessExecutor().waitForLaunchedProcess(launchedProcess);
    }

    if (exitCode != 0) {
      context
          .getConsole()
          .printErrorText(String.format(Locale.US, "xctest failed with exit code %d", exitCode));
    }

    return StepExecutionResult.of(exitCode);
  }

  @Override
  public final String getDescription(ExecutionContext context) {
    return "Run XCTest or SenTestingKit tests";
  }
}
