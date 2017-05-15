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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A step that preprocesses and/or compiles C/C++ sources in a single step. */
public class CxxPreprocessAndCompileStep implements Step {

  private static final Logger LOG = Logger.get(CxxPreprocessAndCompileStep.class);

  private final BuildTarget target;
  private final ProjectFilesystem filesystem;
  private final Operation operation;
  private final Path output;
  private final Optional<Path> depFile;
  private final Path input;
  private final CxxSource.Type inputType;
  private final ToolCommand command;
  private final HeaderPathNormalizer headerPathNormalizer;
  private final DebugPathSanitizer sanitizer;
  private final Compiler compiler;

  /** Directory to use to store intermediate/temp files used for compilation. */
  private final Path scratchDir;

  private final boolean useArgfile;

  private static final FileLastModifiedDateContentsScrubber FILE_LAST_MODIFIED_DATE_SCRUBBER =
      new FileLastModifiedDateContentsScrubber();

  public CxxPreprocessAndCompileStep(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Operation operation,
      Path output,
      Optional<Path> depFile,
      Path input,
      CxxSource.Type inputType,
      ToolCommand command,
      HeaderPathNormalizer headerPathNormalizer,
      DebugPathSanitizer sanitizer,
      Path scratchDir,
      boolean useArgfile,
      Compiler compiler) {
    this.target = target;
    this.filesystem = filesystem;
    this.operation = operation;
    this.output = output;
    this.depFile = depFile;
    this.input = input;
    this.inputType = inputType;
    this.command = command;
    this.headerPathNormalizer = headerPathNormalizer;
    this.sanitizer = sanitizer.withProjectFilesystem(filesystem);
    this.scratchDir = scratchDir;
    this.useArgfile = useArgfile;
    this.compiler = compiler;
  }

  @Override
  public String getShortName() {
    return inputType.getLanguage() + " " + operation.toString().toLowerCase();
  }

  /**
   * Apply common settings for our subprocesses.
   *
   * @return Half-configured ProcessExecutorParams.Builder
   */
  private ProcessExecutorParams.Builder makeSubprocessBuilder(ExecutionContext context) {
    Map<String, String> env = new HashMap<>(context.getEnvironment());

    env.putAll(
        sanitizer.getCompilationEnvironment(
            filesystem.getRootPath().toAbsolutePath(), shouldSanitizeOutputBinary()));

    // Set `TMPDIR` to `scratchDir` so the compiler/preprocessor uses this dir for it's temp and
    // intermediate files.
    env.put("TMPDIR", filesystem.resolve(scratchDir).toString());

    // Add some diagnostic strings into the subprocess's env as well.
    // Note: the current process's env already contains `BUCK_BUILD_ID`, which will be inherited.
    env.put("BUCK_BUILD_TARGET", target.toString());

    return ProcessExecutorParams.builder()
        .setDirectory(filesystem.getRootPath().toAbsolutePath())
        .setRedirectError(ProcessBuilder.Redirect.PIPE)
        .setEnvironment(ImmutableMap.copyOf(env));
  }

  private Path getArgfile() {
    return filesystem.resolve(scratchDir).resolve("ppandcompile.argsfile");
  }

  @VisibleForTesting
  ImmutableList<String> getArguments(boolean allowColorsInDiagnostics) {
    String inputLanguage =
        operation == Operation.GENERATE_PCH
            ? inputType.getPrecompiledHeaderLanguage().get()
            : inputType.getLanguage();
    return ImmutableList.<String>builder()
        .addAll(command.getArguments())
        .addAll(
            (allowColorsInDiagnostics
                    ? compiler.getFlagsForColorDiagnostics()
                    : Optional.<ImmutableList<String>>empty())
                .orElseGet(ImmutableList::of))
        .addAll(compiler.languageArgs(inputLanguage))
        .addAll(sanitizer.getCompilationFlags())
        .add("-c")
        .addAll(
            depFile
                .map(depFile -> compiler.outputDependenciesArgs(depFile.toString()))
                .orElseGet(ImmutableList::of))
        .add(input.toString())
        .addAll(compiler.outputArgs(output.toString()))
        .build();
  }

  private int executeCompilation(ExecutionContext context) throws Exception {
    ProcessExecutorParams.Builder builder = makeSubprocessBuilder(context);

    if (useArgfile) {
      filesystem.writeLinesToPath(
          Iterables.transform(
              getArguments(context.getAnsi().isAnsiTerminal()), Escaper.ARGFILE_ESCAPER),
          getArgfile());
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(command.getCommandPrefix())
              .add("@" + getArgfile())
              .build());
    } else {
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(command.getCommandPrefix())
              .addAll(getArguments(context.getAnsi().isAnsiTerminal()))
              .build());
    }

    ProcessExecutorParams params = builder.build();

    LOG.debug("Running command (pwd=%s): %s", params.getDirectory(), getDescription(context));

    // Start the process.
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.LaunchedProcess process = executor.launchProcess(params);

    // We buffer error messages in memory, as these are typically small.
    String err;
    int exitCode;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(compiler.getErrorStream(process)))) {
      CxxErrorTransformer cxxErrorTransformer =
          new CxxErrorTransformer(
              filesystem, context.shouldReportAbsolutePaths(), headerPathNormalizer);
      err =
          reader.lines().map(cxxErrorTransformer::transformLine).collect(Collectors.joining("\n"));
      exitCode = executor.waitForLaunchedProcess(process).getExitCode();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    } finally {
      executor.destroyLaunchedProcess(process);
      executor.waitForLaunchedProcess(process);
    }

    // If we generated any error output, print that to the console.
    if (!err.isEmpty()) {
      context
          .getBuckEventBus()
          .post(
              createConsoleEvent(
                  context,
                  compiler.getFlagsForColorDiagnostics().isPresent(),
                  exitCode == 0 ? Level.WARNING : Level.SEVERE,
                  err));
    }

    return exitCode;
  }

  private ConsoleEvent createConsoleEvent(
      ExecutionContext context, boolean commandOutputsColor, Level level, String message) {
    if (context.getAnsi().isAnsiTerminal() && commandOutputsColor) {
      return ConsoleEvent.createForMessageWithAnsiEscapeCodes(level, message);
    } else {
      return ConsoleEvent.create(level, message);
    }
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    try {
      LOG.debug("%s %s -> %s", operation.toString().toLowerCase(), input, output);

      int exitCode = executeCompilation(context);

      // If the compilation completed successfully and we didn't effect debug-info normalization
      // through #line directive modification, perform the in-place update of the compilation per
      // above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (exitCode == 0 && shouldSanitizeOutputBinary()) {
        try {
          Path path = filesystem.getRootPath().toAbsolutePath().resolve(output);
          sanitizer.restoreCompilationDirectory(path, filesystem.getRootPath().toAbsolutePath());
          FILE_LAST_MODIFIED_DATE_SCRUBBER.scrubFileWithPath(path);
        } catch (IOException e) {
          context.logError(e, "error updating compilation directory");
          return StepExecutionResult.ERROR;
        }
      }

      if (exitCode != 0) {
        LOG.warn("error %d %s %s", exitCode, operation.toString().toLowerCase(), input);
      }

      return StepExecutionResult.of(exitCode);

    } catch (Exception e) {
      MoreThrowables.propagateIfInterrupt(e);
      context.logError(e, "Build error caused by exception");
      return StepExecutionResult.ERROR;
    }
  }

  ImmutableList<String> getCommand() {
    // We set allowColorsInDiagnostics to false here because this function is only used by the
    // compilation database (its contents should not depend on how Buck was invoked) and in the
    // step's description. It is not used to determine what command this step runs, which needs
    // to decide whether to use colors or not based on whether the terminal supports them.
    return ImmutableList.<String>builder()
        .addAll(command.getCommandPrefix())
        .addAll(getArguments(false))
        .build();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    if (context.getVerbosity().shouldPrintCommand()) {
      return Stream.concat(command.getCommandPrefix().stream(), getArguments(false).stream())
          .map(Escaper.SHELL_ESCAPER::apply)
          .collect(Collectors.joining(" "));
    }
    return "(verbosity level disables command output)";
  }

  private boolean shouldSanitizeOutputBinary() {
    return inputType.isAssembly()
        || (operation == Operation.PREPROCESS_AND_COMPILE && compiler.shouldSanitizeOutputBinary());
  }

  public enum Operation {
    /** Run only the compiler on source files. */
    COMPILE,
    /** Run the preprocessor and compiler on source files. */
    PREPROCESS_AND_COMPILE,
    GENERATE_PCH,
    ;
  }

  public static class ToolCommand {
    private final ImmutableList<String> commandPrefix;
    private final ImmutableList<String> arguments;
    private final ImmutableMap<String, String> environment;

    public ToolCommand(
        ImmutableList<String> commandPrefix,
        ImmutableList<String> arguments,
        ImmutableMap<String, String> environment) {
      this.commandPrefix = commandPrefix;
      this.arguments = arguments;
      this.environment = environment;
    }

    public ImmutableList<String> getCommandPrefix() {
      return commandPrefix;
    }

    public ImmutableList<String> getArguments() {
      return arguments;
    }

    public ImmutableMap<String, String> getEnvironment() {
      return environment;
    }
  }
}
