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

package com.facebook.buck.swift;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A step that compiles Swift sources to a single module. */
class SwiftCompileStep extends SwiftCompileStepBase {

  private static final Logger LOG = Logger.get(SwiftCompileStep.class);

  private final ImmutableMap<String, String> compilerEnvironment;
  private final Optional<AbsPath> argfilePath;
  private final boolean transformErrorsToAbsolutePaths;

  SwiftCompileStep(
      AbsPath compilerCwd,
      Map<String, String> compilerEnvironment,
      ImmutableList<String> compilerCommandPrefix,
      ImmutableList<String> compilerCommandArguments,
      ProjectFilesystem filesystem,
      Optional<AbsPath> argfilePath,
      boolean withDownwardApi,
      boolean transformErrorsToAbsolutePaths) {
    super(
        compilerCwd, compilerCommandPrefix, compilerCommandArguments, filesystem, withDownwardApi);
    this.compilerEnvironment = ImmutableMap.copyOf(compilerEnvironment);
    this.argfilePath = argfilePath;
    this.transformErrorsToAbsolutePaths = transformErrorsToAbsolutePaths;
  }

  @Override
  public String getShortName() {
    return "swift compile";
  }

  private ProcessExecutorParams makeProcessExecutorParams(StepExecutionContext context)
      throws IOException {
    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setDirectory(compilerCwd.getPath());
    builder.setEnvironment(compilerEnvironment);

    Iterable<String> colorArguments = getColorArguments(context.getAnsi().isAnsiTerminal());

    if (argfilePath.isPresent()) {
      AbsPath argfile = argfilePath.get();
      AbsPath argfileDir = argfile.getParent();
      if (Files.notExists(argfileDir.getPath())) {
        Files.createDirectories(argfileDir.getPath());
      }

      Iterable<String> escapedArgs =
          Iterables.transform(compilerCommandArguments, Escaper.ARGFILE_ESCAPER::apply);
      filesystem.writeLinesToPath(escapedArgs, argfile.getPath());

      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(compilerCommandPrefix)
              .add("@" + argfile.toString())
              .addAll(colorArguments)
              .build());
    } else {
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(compilerCommandPrefix)
              .addAll(compilerCommandArguments)
              .addAll(colorArguments)
              .build());
    }

    return builder.build();
  }

  private Iterable<String> getColorArguments(boolean allowColorInDiagnostics) {
    return allowColorInDiagnostics ? ImmutableList.of("-color-diagnostics") : ImmutableList.of();
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutorParams params = makeProcessExecutorParams(context);

    LOG.debug("%s", getRawCommand());

    boolean willTransformStderr =
        transformErrorsToAbsolutePaths && context.shouldReportAbsolutePaths();

    // When we transform the stderr output, we need to use an executor that will not output to
    // stderr by default. Otherwise, we'll print any compiler errors twice: once with a relative
    // path, and once transformed with a relative path.
    ProcessExecutor processExecutor =
        willTransformStderr
            ? new DefaultProcessExecutor(Console.createNullConsole())
            : context.getProcessExecutor();

    if (withDownwardApi) {
      processExecutor =
          processExecutor.withDownwardAPI(
              DownwardApiProcessExecutor.FACTORY, context.getBuckEventBus().isolated());
    }
    Result processResult =
        getTransformedProcessResult(processExecutor.launchAndExecute(params), willTransformStderr);

    int result = processResult.getExitCode();
    boolean success = result == StepExecutionResults.SUCCESS_EXIT_CODE;
    Optional<String> stderr = processResult.getStderr();

    if ((willTransformStderr || success) && stderr.isPresent()) {
      boolean usingColor = context.getAnsi().isAnsiTerminal();
      Level level = success ? Level.WARNING : Level.SEVERE;
      context
          .getBuckEventBus()
          .post(
              usingColor
                  ? ConsoleEvent.createForMessageWithAnsiEscapeCodes(level, stderr.get())
                  : ConsoleEvent.create(level, stderr.get()));
    }

    return StepExecutionResult.of(processResult);
  }

  private Result getTransformedProcessResult(Result result, boolean willTransformStderr)
      throws IOException {
    if (willTransformStderr && result.getStderr().isPresent()) {
      String stderr = result.getStderr().get();
      Stream<String> stderrLines = MoreStrings.lines(stderr).stream();

      SwiftErrorTransformer transformer = new SwiftErrorTransformer(filesystem);
      String transformedStderr =
          stderrLines
              .map(line -> transformer.transformLine(line))
              .collect(Collectors.joining(System.lineSeparator()));

      return new Result(
          result.getExitCode(),
          result.isTimedOut(),
          result.getStdout(),
          Optional.of(transformedStderr),
          result.getCommand());
    } else {
      return result;
    }
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return Joiner.on(" ").join(getRawCommand());
  }
}
