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
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.LineProcessorRunnable;
import com.facebook.buck.util.ManagedRunnable;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import javax.annotation.Nullable;

/**
 * A step that preprocesses and/or compiles C/C++ sources in a single step.
 */
public class CxxPreprocessAndCompileStep implements Step {

  private static final Logger LOG = Logger.get(CxxPreprocessAndCompileStep.class);

  private final ProjectFilesystem filesystem;
  private final Operation operation;
  private final Path output;
  private final Path depFile;
  private final Path input;
  private final CxxSource.Type inputType;
  private final Optional<ToolCommand> preprocessorCommand;
  private final Optional<ToolCommand> compilerCommand;
  private final HeaderPathNormalizer headerPathNormalizer;
  private final DebugPathSanitizer sanitizer;
  private final HeaderVerification headerVerification;
  private final Compiler compiler;

  /**
   * Directory to use to store intermediate/temp files used for compilation.
   */
  private final Path scratchDir;
  private final boolean useArgfile;
  private static final FileLastModifiedDateContentsScrubber FILE_LAST_MODIFIED_DATE_SCRUBBER =
      new FileLastModifiedDateContentsScrubber();

  public CxxPreprocessAndCompileStep(
      ProjectFilesystem filesystem,
      Operation operation,
      Path output,
      Path depFile,
      Path input,
      CxxSource.Type inputType,
      Optional<ToolCommand> preprocessorCommand,
      Optional<ToolCommand> compilerCommand,
      HeaderPathNormalizer headerPathNormalizer,
      DebugPathSanitizer sanitizer,
      HeaderVerification headerVerification,
      Path scratchDir,
      boolean useArgfile,
      Compiler compiler) {

    Preconditions.checkState(operation.isPreprocess() == preprocessorCommand.isPresent());
    Preconditions.checkState(operation.isCompile() == compilerCommand.isPresent());

    this.filesystem = filesystem;
    this.operation = operation;
    this.output = output;
    this.depFile = depFile;
    this.input = input;
    this.inputType = inputType;
    this.preprocessorCommand = preprocessorCommand;
    this.compilerCommand = compilerCommand;
    this.headerPathNormalizer = headerPathNormalizer;
    this.sanitizer = sanitizer;
    this.headerVerification = headerVerification;
    this.scratchDir = scratchDir;
    this.useArgfile = useArgfile;
    this.compiler = compiler;
  }

  @Override
  public String getShortName() {
    Optional<CxxSource.Type> type = CxxSource.Type.fromExtension(
        Files.getFileExtension(input.getFileName().toString()));
    String fileType;
    if (type.isPresent()) {
      fileType = type.get().getLanguage();
    } else {
      fileType = "unknown";
    }
    return fileType + " " + operation.toString().toLowerCase();
  }

  /**
   * Apply common settings for our subprocesses.
   *
   * @return Half-configured ProcessExecutorParams.Builder
   */
  private ProcessExecutorParams.Builder makeSubprocessBuilder(
      ExecutionContext context,
      Map<String, String> additionalEnvironment) {
    Map<String, String> env = new HashMap<>(context.getEnvironment());

    env.putAll(
        sanitizer.getCompilationEnvironment(
            filesystem.getRootPath().toAbsolutePath(),
            shouldSanitizeOutputBinary()));

    // Set `TMPDIR` to `scratchDir` so the compiler/preprocessor uses this dir for it's temp and
    // intermediate files.
    env.put("TMPDIR", filesystem.resolve(scratchDir).toString());
    // Add additional environment variables.
    env.putAll(additionalEnvironment);

    return ProcessExecutorParams.builder()
        .setDirectory(filesystem.getRootPath().toAbsolutePath())
        .setRedirectError(ProcessBuilder.Redirect.PIPE)
        .setEnvironment(env);
  }

  private Path getDepTemp() {
    return filesystem.resolve(scratchDir).resolve("dep.tmp");
  }

  private ImmutableList<String> getDepFileArgs(Path depFile) {
    return compiler.outputDependenciesArgs(depFile.toString());
  }

  private ImmutableList<String> getLanguageArgs(String inputLanguage) {
    return compiler.languageArgs(inputLanguage);
  }

  private Path getArgfile() {
    return filesystem.resolve(scratchDir).resolve("argfile.txt");
  }

  @VisibleForTesting
  ImmutableList<String> makePreprocessArguments(boolean allowColorsInDiagnostics) {
    return ImmutableList.<String>builder()
        .addAll(preprocessorCommand.get().getArguments(allowColorsInDiagnostics))
        .addAll(getLanguageArgs(inputType.getLanguage()))
        .add("-E")
        .addAll(getDepFileArgs(getDepTemp()))
        .add(input.toString())
        .build();
  }

  @VisibleForTesting
  ImmutableList<String> makeCompileArguments(
      String inputFileName,
      String inputLanguage,
      boolean preprocessable,
      boolean allowColorsInDiagnostics) {
    return ImmutableList.<String>builder()
        .addAll(compilerCommand.get().getArguments(allowColorsInDiagnostics))
        .addAll(getLanguageArgs(inputLanguage))
        .add("-c")
        .addAll(
            preprocessable ?
                getDepFileArgs(getDepTemp()) :
                ImmutableList.of())
        .add(inputFileName)
        .addAll(compiler.outputArgs(output.toString()))
        .build();
  }

  private ImmutableList<String> makeGeneratePchArguments(boolean allowColorInDiagnostics) {
    return ImmutableList.<String>builder()
        .addAll(preprocessorCommand.get().getArguments(allowColorInDiagnostics))
        // Using x-header language type directs the compiler to generate a PCH file.
        .addAll(getLanguageArgs(inputType.getPrecompiledHeaderLanguage().get()))
        // PCH file generation can also output dep files.
        .addAll(getDepFileArgs(getDepTemp()))
        .add(input.toString())
        .add("-o", output.toString())
        .build();
  }

  private void safeCloseProcessor(@Nullable ManagedRunnable processor) {
    if (processor != null) {
      try {
        processor.waitFor();
        processor.close();
      } catch (Exception ex) {
        LOG.warn(ex, "error closing processor");
      }
    }
  }

  private int executePiped(ExecutionContext context)
      throws IOException, InterruptedException {
    Preconditions.checkState(preprocessorCommand.isPresent());
    Preconditions.checkState(compilerCommand.isPresent());
    ByteArrayOutputStream preprocessError = new ByteArrayOutputStream();
    ProcessExecutorParams preprocessParams = makeSubprocessBuilder(
            context,
            preprocessorCommand.get().getEnvironment())
        .setCommand(
            ImmutableList.<String>builder()
                .addAll(preprocessorCommand.get().getCommandPrefix())
                .addAll(makePreprocessArguments(context.getAnsi().isAnsiTerminal()))
                .build())
        .setRedirectOutput(ProcessBuilder.Redirect.PIPE)
        .build();

    ByteArrayOutputStream compileError = new ByteArrayOutputStream();
    ProcessExecutorParams compileParams = makeSubprocessBuilder(
            context,
            compilerCommand.get().getEnvironment())
        .setCommand(
            ImmutableList.<String>builder()
                .addAll(compilerCommand.get().getCommandPrefix())
                .addAll(
                    makeCompileArguments(
                        "-",
                        inputType.getPreprocessedLanguage(),
                        /* preprocessable */ false,
                        context.getAnsi().isAnsiTerminal()))
                .build())
        .setRedirectInput(ProcessBuilder.Redirect.PIPE)
        .build();

    ProcessExecutor.LaunchedProcess preprocess = null;
    ProcessExecutor.LaunchedProcess compile = null;
    LineProcessorRunnable errorProcessorPreprocess = null;
    LineProcessorRunnable errorProcessorCompile = null;
    LineProcessorRunnable lineDirectiveMunger = null;

    CxxErrorTransformerFactory errorStreamTransformerFactory =
        createErrorTransformerFactory(context);

    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    try {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Running command (pwd=%s): %s",
            preprocessParams.getDirectory(),
            getDescription(context));
      }

      preprocess = executor.launchProcess(preprocessParams);
      compile = executor.launchProcess(compileParams);

      errorProcessorPreprocess = errorStreamTransformerFactory.createTransformerThread(
          context,
          preprocess.getErrorStream(),
          preprocessError);

        errorProcessorPreprocess.start();

      errorProcessorCompile = errorStreamTransformerFactory.createTransformerThread(
          context,
          compile.getErrorStream(),
          compileError);

      errorProcessorCompile.start();

      lineDirectiveMunger = createPreprocessorOutputTransformerFactory()
          .createTransformerThread(context, preprocess.getInputStream(), compile.getOutputStream());

      lineDirectiveMunger.start();

      int compileStatus = executor.waitForLaunchedProcess(compile).getExitCode();
      int preprocessStatus = executor.waitForLaunchedProcess(preprocess).getExitCode();

      safeCloseProcessor(errorProcessorPreprocess);
      safeCloseProcessor(errorProcessorCompile);

      String preprocessErr = new String(preprocessError.toByteArray());
      if (!preprocessErr.isEmpty()) {
        context.getBuckEventBus().post(
            createConsoleEvent(
                context,
                preprocessorCommand.get().supportsColorsInDiagnostics(),
                preprocessStatus == 0 ? Level.WARNING : Level.SEVERE,
                preprocessErr));
      }

      String compileErr = new String(compileError.toByteArray());
      if (!compileErr.isEmpty()) {
        context.getBuckEventBus().post(
            createConsoleEvent(
                context,
                compilerCommand.get().supportsColorsInDiagnostics(),
                compileStatus == 0 ? Level.WARNING : Level.SEVERE,
                compileErr));
      }

      if (preprocessStatus != 0) {
        LOG.warn("error %d %s(preprocess) %s: %s", preprocessStatus,
            operation.toString().toLowerCase(), input, preprocessErr);
      }

      if (compileStatus != 0) {
        LOG.warn("error %d %s(compile) %s: %s", compileStatus,
            operation.toString().toLowerCase(), input, compileErr);
      }

      if (preprocessStatus != 0) {
        return preprocessStatus;
      }

      if (compileStatus != 0) {
        return compileStatus;
      }

      return 0;
    } finally {
      if (preprocess != null) {
        executor.destroyLaunchedProcess(preprocess);
        executor.waitForLaunchedProcess(preprocess);
      }

      if (compile != null) {
        executor.destroyLaunchedProcess(compile);
        executor.waitForLaunchedProcess(compile);
      }

      safeCloseProcessor(errorProcessorPreprocess);
      safeCloseProcessor(errorProcessorCompile);
      safeCloseProcessor(lineDirectiveMunger);
    }
  }

  private int executeOther(ExecutionContext context) throws Exception {
    ProcessExecutorParams.Builder builder =
        makeSubprocessBuilder(context, ImmutableMap.of());

    if (useArgfile) {
      filesystem.writeLinesToPath(
          Iterables.transform(
              getArguments(context.getAnsi().isAnsiTerminal()),
              Escaper.ARGFILE_ESCAPER),
          getArgfile());
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(getCommandPrefix())
              .add("@" + getArgfile())
              .build());
    } else {
      builder.setCommand(
          ImmutableList.<String>builder()
              .addAll(getCommandPrefix())
              .addAll(getArguments(context.getAnsi().isAnsiTerminal()))
              .build());
    }
    // If we're preprocessing, file output goes through stdout, so we can postprocess it.
    if (operation == Operation.PREPROCESS) {
      builder.setRedirectOutput(ProcessBuilder.Redirect.PIPE);
    }
    ProcessExecutorParams params = builder.build();

    LOG.debug(
        "Running command (pwd=%s): %s",
        params.getDirectory(),
        getDescription(context));

    // Start the process.
    ProcessExecutor executor = new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.LaunchedProcess process = executor.launchProcess(params);

    // We buffer error messages in memory, as these are typically small.
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    // Open the temp file to write the intermediate output to and also fire up managed threads
    // to process the stdout and stderr lines from the preprocess command.
    int exitCode;
    try {
      try (LineProcessorRunnable errorProcessor =
               createErrorTransformerFactory(context)
                   .createTransformerThread(context, process.getErrorStream(), error)) {
        errorProcessor.start();

        // If we're preprocessing, we pipe the output through a processor to sanitize the line
        // markers.  So fire that up...
        if (operation == Operation.PREPROCESS) {
          try (OutputStream output =
                   filesystem.newFileOutputStream(this.output);
               LineProcessorRunnable outputProcessor =
                   createPreprocessorOutputTransformerFactory()
                       .createTransformerThread(context, process.getInputStream(), output)) {
            outputProcessor.start();
            outputProcessor.waitFor();
          } catch (Throwable thrown) {
            executor.destroyLaunchedProcess(process);
            throw thrown;
          }
        }
        errorProcessor.waitFor();
      } catch (Throwable thrown) {
        executor.destroyLaunchedProcess(process);
        throw thrown;
      }
      exitCode = executor.waitForLaunchedProcess(process).getExitCode();
    } finally {
      executor.destroyLaunchedProcess(process);
      executor.waitForLaunchedProcess(process);
    }

    // If we generated any error output, print that to the console.
    String err = new String(error.toByteArray());
    if (!err.isEmpty()) {
      context.getBuckEventBus().post(
          createConsoleEvent(
              context,
              preprocessorCommand.map(Optional::of).orElse(compilerCommand).get()
                  .supportsColorsInDiagnostics(),
              exitCode == 0 ? Level.WARNING : Level.SEVERE,
              err));
    }

    return exitCode;
  }

  private ConsoleEvent createConsoleEvent(
      ExecutionContext context,
      boolean commandOutputsColor,
      Level level,
      String message) {
    if (context.getAnsi().isAnsiTerminal() && commandOutputsColor) {
      return ConsoleEvent.createForMessageWithAnsiEscapeCodes(level, message);
    } else {
      return ConsoleEvent.create(level, message);
    }
  }

  private CxxPreprocessorOutputTransformerFactory createPreprocessorOutputTransformerFactory() {
    return new CxxPreprocessorOutputTransformerFactory(
        filesystem.getRootPath(),
        headerPathNormalizer,
        sanitizer);
  }

  private CxxErrorTransformerFactory createErrorTransformerFactory(ExecutionContext context) {
    return new CxxErrorTransformerFactory(
        // If we're compiling, we also need to restore the original working directory in the
        // error output.
        operation == Operation.COMPILE ?
            Optional.of(filesystem.getRootPath()) :
            Optional.empty(),
        context.shouldReportAbsolutePaths() ?
            Optional.of(filesystem.getAbsolutifier()) :
            Optional.empty(),
        headerPathNormalizer,
        sanitizer);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
    try {
      LOG.debug("%s %s -> %s", operation.toString().toLowerCase(), input, output);

      // We need completely different logic if we're piping from the preprocessor to the compiler.
      int exitCode;
      if (operation == Operation.PIPED_PREPROCESS_AND_COMPILE) {
        exitCode = executePiped(context);
      } else {
        exitCode = executeOther(context);
      }

      if (operation.isPreprocess() && exitCode == 0 && compiler.isDependencyFileSupported()) {
        exitCode =
            Depfiles.parseAndWriteBuckCompatibleDepfile(
                context,
                filesystem,
                headerPathNormalizer,
                headerVerification,
                getDepTemp(),
                depFile,
                input,
                output);
      }

      // If the compilation completed successfully and we didn't effect debug-info normalization
      // through #line directive modification, perform the in-place update of the compilation per
      // above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (exitCode == 0 && shouldSanitizeOutputBinary()) {
        try {
          Path path = filesystem.getRootPath().toAbsolutePath().resolve(output);
          sanitizer.restoreCompilationDirectory(
              path,
              filesystem.getRootPath().toAbsolutePath());
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

  public ImmutableList<String> getCommand() {
    // We set allowColorsInDiagnostics to false here because this function is only used by the
    // compilation database (its contents should not depend on how Buck was invoked) and in the
    // step's description. It is not used to determine what command this step runs, which needs
    // to decide whether to use colors or not based on whether the terminal supports them.
    return ImmutableList.<String>builder()
        .addAll(getCommandPrefix())
        .addAll(getArguments(false))
        .build();
  }

  public ImmutableList<String> getCommandPrefix() {
    switch (operation) {
      case COMPILE:
      case COMPILE_MUNGE_DEBUGINFO:
        return compilerCommand.get().getCommandPrefix();
      case PREPROCESS:
      case GENERATE_PCH:
        return preprocessorCommand.get().getCommandPrefix();
      // $CASES-OMITTED$
      default:
        throw new RuntimeException("invalid operation type");
    }
  }

  public ImmutableList<String> getArguments(boolean allowColorsInDiagnostics) {
    switch (operation) {
      case COMPILE:
      case COMPILE_MUNGE_DEBUGINFO:
        return makeCompileArguments(
            input.toString(),
            inputType.getLanguage(),
            inputType.isPreprocessable(),
            allowColorsInDiagnostics);
      case PREPROCESS:
        return makePreprocessArguments(allowColorsInDiagnostics);
      case GENERATE_PCH:
        return makeGeneratePchArguments(allowColorsInDiagnostics);
      // $CASES-OMITTED$
      default:
        throw new RuntimeException("invalid operation type");
    }
  }

  public String getDescriptionNoContext() {
    switch (operation) {
      case PIPED_PREPROCESS_AND_COMPILE: {
        return Joiner.on(' ').join(
            FluentIterable.from(preprocessorCommand.get().getCommandPrefix())
                .append(makePreprocessArguments(/* allowColorsInDiagnostics */ false))
                .transform(Escaper.SHELL_ESCAPER)) +
            " | " +
            Joiner.on(' ').join(
                FluentIterable.from(compilerCommand.get().getCommandPrefix())
                    .append(
                        makeCompileArguments(
                            "-",
                            inputType.getPreprocessedLanguage(),
                            /* preprocessable */ false,
                            /* allowColorsInDiagnostics */ false))
                    .transform(Escaper.SHELL_ESCAPER));

      }
      // $CASES-OMITTED$
      default: {
        return Joiner.on(' ').join(
            FluentIterable.from(getCommandPrefix())
                .append(getArguments(false))
                .transform(Escaper.SHELL_ESCAPER));
      }
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    if (context.getVerbosity().shouldPrintCommand()) {
      return getDescriptionNoContext();
    }
    return "(verbosity level disables command output)";
  }

  // We need to do binary rewriting if doing combined preprocessing and compiling or if we're
  // building assembly code (which doesn't respect line-marker-re-writing to fixup the
  // DW_AT_comp_dir.
  private boolean shouldSanitizeOutputBinary() {
    return operation == Operation.COMPILE_MUNGE_DEBUGINFO || inputType.isAssembly();
  }

  public enum Operation {
    /**
     * Run the compiler on post-preprocessed source files.
     */
    COMPILE,
    /**
     * Run the preprocessor and compiler on source files.
     */
    COMPILE_MUNGE_DEBUGINFO,
    /**
     * Preprocess a source file.
     */
    PREPROCESS,
    /**
     * Run the preprocessor and compiler separately, piping the output of the preprocessor to the
     * compiler.
     */
    PIPED_PREPROCESS_AND_COMPILE,
    GENERATE_PCH,
    ;

    /**
     * Returns whether the step has a preprocessor component.
     */
    public boolean isPreprocess() {
      switch (this) {
        case COMPILE_MUNGE_DEBUGINFO:
        case PREPROCESS:
        case PIPED_PREPROCESS_AND_COMPILE:
        case GENERATE_PCH:
          return true;
        case COMPILE:
          return false;
      }
      throw new RuntimeException("unhandled case");
    }

    /**
     * Returns whether the step has a compilation component.
     */
    public boolean isCompile() {
      switch (this) {
        case COMPILE:
        case COMPILE_MUNGE_DEBUGINFO:
        case PIPED_PREPROCESS_AND_COMPILE:
          return true;
        case PREPROCESS:
        case GENERATE_PCH:
          return false;
      }
      throw new RuntimeException("unhandled case");
    }
  }

  public static class ToolCommand {
    private final ImmutableList<String> commandPrefix;
    private final ImmutableList<String> arguments;
    private final ImmutableMap<String, String> environment;
    private final Optional<ImmutableList<String>> flagsForColorDiagnostics;

    public ToolCommand(
        ImmutableList<String> commandPrefix,
        ImmutableList<String> arguments,
        ImmutableMap<String, String> environment,
        Optional<ImmutableList<String>> flagsForColorDiagnostics) {
      this.commandPrefix = commandPrefix;
      this.arguments = arguments;
      this.environment = environment;
      this.flagsForColorDiagnostics = flagsForColorDiagnostics;
    }

    public ImmutableList<String> getCommandPrefix() {
      return commandPrefix;
    }

    public ImmutableList<String> getArguments(boolean allowColorsInDiagnostics) {
      if (allowColorsInDiagnostics && flagsForColorDiagnostics.isPresent()) {
        return ImmutableList.<String>builder()
            .addAll(arguments)
            .addAll(flagsForColorDiagnostics.get())
            .build();
      } else {
        return arguments;
      }
    }

    public ImmutableMap<String, String> getEnvironment() {
      return environment;
    }

    public boolean supportsColorsInDiagnostics() {
      return flagsForColorDiagnostics.isPresent();
    }
  }

}
