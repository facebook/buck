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

import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FunctionLineProcessorThread;
import com.facebook.buck.util.MoreThrowables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A step that preprocesses and/or compiles C/C++ sources in a single step.
 */
public class CxxPreprocessAndCompileStep implements Step {

  private static final Logger LOG = Logger.get(CxxPreprocessAndCompileStep.class);

  private final Operation operation;
  private final Path output;
  private final Path input;
  private final CxxSource.Type inputType;
  private final Optional<ImmutableList<String>> preprocessorCommand;
  private final Optional<ImmutableList<String>> compilerCommand;
  private final ImmutableMap<Path, Path> replacementPaths;
  private final DebugPathSanitizer sanitizer;

  // N.B. These include paths are special to GCC. They aren't real files and there is no remapping
  // needed, so we can just ignore them everywhere.
  private static final ImmutableSet<String> SPECIAL_INCLUDE_PATHS = ImmutableSet.of(
      "<built-in>",
      "<command-line>"
  );

  public CxxPreprocessAndCompileStep(
      Operation operation,
      Path output,
      Path input,
      CxxSource.Type inputType,
      Optional<ImmutableList<String>> preprocessorCommand,
      Optional<ImmutableList<String>> compilerCommand,
      ImmutableMap<Path, Path> replacementPaths,
      DebugPathSanitizer sanitizer) {
    Preconditions.checkState(operation.isPreprocess() == preprocessorCommand.isPresent());
    Preconditions.checkState(operation.isCompile() == compilerCommand.isPresent());
    this.operation = operation;
    this.output = output;
    this.input = input;
    this.inputType = inputType;
    this.preprocessorCommand = preprocessorCommand;
    this.compilerCommand = compilerCommand;
    this.replacementPaths = replacementPaths;
    this.sanitizer = sanitizer;
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

  @VisibleForTesting
  Function<String, Iterable<String>> createPreprocessOutputLineProcessor(final Path workingDir) {
    return new Function<String, Iterable<String>>() {

      private final Pattern lineMarkers =
          Pattern.compile("^# (?<num>\\d+) \"(?<path>[^\"]+)\"(?<rest>.*)?$");

      @Override
      public Iterable<String> apply(String line) {
        if (line.startsWith("# ")) {
          Matcher m = lineMarkers.matcher(line);

          if (m.find() && !SPECIAL_INCLUDE_PATHS.contains(m.group("path"))) {
            String originalPath = m.group("path");
            String replacementPath = originalPath;

            replacementPath = Optional
                .fromNullable(replacementPaths.get(Paths.get(replacementPath)))
                .transform(Escaper.PATH_FOR_C_INCLUDE_STRING_ESCAPER)
                .or(replacementPath);

            replacementPath =
                sanitizer.sanitize(
                    Optional.of(workingDir),
                    replacementPath,
                    /* expandPaths */ false);

            if (!originalPath.equals(replacementPath)) {
              String num = m.group("num");
              String rest = m.group("rest");
              return ImmutableList.of("# " + num + " \"" + replacementPath + "\"" + rest);
            }
          }
        }
        return ImmutableList.of(line);
      }
    };
  }

  @VisibleForTesting
  Function<String, Iterable<String>> createErrorLineProcessor(final Path workingDir) {
    return CxxDescriptionEnhancer.createErrorMessagePathProcessor(
        new Function<String, String>() {
          @Override
          public String apply(String original) {
            Path path = Paths.get(original);

            // If we're compiling, we also need to restore the original working directory in the
            // error output.
            if (operation == Operation.COMPILE) {
              path = Paths.get(sanitizer.restore(Optional.of(workingDir), original));
            }

            // And, of course, we need to fixup any replacement paths.
            return Optional
                .fromNullable(replacementPaths.get(path))
                .transform(Escaper.PATH_FOR_C_INCLUDE_STRING_ESCAPER)
                .or(Escaper.escapePathForCIncludeString(path));
          }
        });
  }

  /**
   * Apply common settings for our subprocesses.
   *
   * @return Half-configured ProcessBuilder
   */
  private ProcessBuilder makeSubprocessBuilder(ExecutionContext context) {
    ProcessBuilder builder = new ProcessBuilder();
    builder.directory(context.getProjectDirectoryRoot().toAbsolutePath().toFile());
    builder.redirectError(ProcessBuilder.Redirect.PIPE);

    // A forced compilation directory is set in the constructor.  Now, we can't actually force
    // the compiler to embed this into the binary -- all we can do set the PWD environment to
    // variations of the actual current working directory (e.g. /actual/dir or
    // /actual/dir////).  This adjustment serves two purposes:
    //
    //   1) it makes the compiler's current-directory line directive output agree with its cwd,
    //      given by getProjectDirectoryRoot.  (If PWD and cwd are different names for the same
    //      directory, the compiler prefers PWD, but we expect cwd for DebugPathSanitizer.)
    //
    //   2) in the case where we're using post-linkd debug path replacement, we reserve room
    //      to expand the path later.
    //
    builder.environment().put(
        "PWD",
        // We only need to expand the working directory if compiling, as some compilers
        // (e.g. clang) ignore overrides set in the preprocessed source when embedding the
        // working directory in the debug info.
        operation == Operation.COMPILE_MUNGE_DEBUGINFO ?
            sanitizer.getExpandedPath(context.getProjectDirectoryRoot().toAbsolutePath()) :
            context.getProjectDirectoryRoot().toAbsolutePath().toString());

    return builder;
  }

  private ImmutableList<String> makePreprocessCommand() {
    return ImmutableList.<String>builder()
        .addAll(preprocessorCommand.get())
        .add("-x", inputType.getLanguage())
        .add("-E")
        .add(input.toString())
        .build();
  }

  private ImmutableList<String> makeCompileCommand(
      String inputFileName,
      String inputLanguage) {
    return ImmutableList.<String>builder()
        .addAll(compilerCommand.get())
        .add("-x", inputLanguage)
        .add("-c")
        .add(inputFileName)
        .add("-o")
        .add(output.toString())
        .build();
  }

  private void safeCloseProcessor(@Nullable FunctionLineProcessorThread processor) {
    if (processor != null) {
      try {
        processor.close();
      } catch (Exception ex) {
        LOG.warn(ex, "error closing processor");
      }
    }
  }

  private int executePiped(ExecutionContext context)
      throws IOException, InterruptedException {
    ByteArrayOutputStream preprocessError = new ByteArrayOutputStream();
    ProcessBuilder preprocessBuilder = makeSubprocessBuilder(context);
    preprocessBuilder.command(makePreprocessCommand());
    preprocessBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);

    ByteArrayOutputStream compileError = new ByteArrayOutputStream();
    ProcessBuilder compileBuilder = makeSubprocessBuilder(context);
    compileBuilder.command(
        makeCompileCommand(
            "-",
            inputType.getPreprocessedLanguage()));
    compileBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);

    Process preprocess = null;
    Process compile = null;
    FunctionLineProcessorThread errorProcessorPreprocess = null;
    FunctionLineProcessorThread errorProcessorCompile = null;
    FunctionLineProcessorThread lineDirectiveMunger = null;

    try {
      LOG.debug(
          "Running command (pwd=%s): %s",
          preprocessBuilder.directory(),
          getDescription(context));

      preprocess = preprocessBuilder.start();
      compile = compileBuilder.start();

      errorProcessorPreprocess =
          new FunctionLineProcessorThread(
              preprocess.getErrorStream(),
              preprocessError,
              createErrorLineProcessor(context.getProjectDirectoryRoot()));
      errorProcessorPreprocess.start();

      errorProcessorCompile =
          new FunctionLineProcessorThread(
              compile.getErrorStream(),
              compileError,
              createErrorLineProcessor(context.getProjectDirectoryRoot()));
      errorProcessorCompile.start();

      lineDirectiveMunger =
          new FunctionLineProcessorThread(
              preprocess.getInputStream(),
              compile.getOutputStream(),
              createPreprocessOutputLineProcessor(context.getProjectDirectoryRoot()));
      lineDirectiveMunger.start();

      int compileStatus = compile.waitFor();
      int preprocessStatus = preprocess.waitFor();

      String preprocessErr = new String(preprocessError.toByteArray());
      if (!preprocessErr.isEmpty()) {
        context.getConsole().printErrorText(preprocessErr);
      }

      String compileErr = new String(compileError.toByteArray());
      if (!compileErr.isEmpty()) {
        context.getConsole().printErrorText(compileErr);
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
        preprocess.destroy();
        preprocess.waitFor();
      }

      if (compile != null) {
        compile.destroy();
        compile.waitFor();
      }

      safeCloseProcessor(errorProcessorPreprocess);
      safeCloseProcessor(errorProcessorCompile);
      safeCloseProcessor(lineDirectiveMunger);
    }
  }

  private int executeOther(ExecutionContext context) throws Exception {
    ProcessBuilder builder = makeSubprocessBuilder(context);

    // If we're preprocessing, file output goes through stdout, so we can postprocess it.
    if (operation == Operation.PREPROCESS) {
      builder.command(makePreprocessCommand());
      builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
    } else {
      builder.command(
          makeCompileCommand(
              input.toString(),
              inputType.getLanguage()));
    }

    LOG.debug(
        "Running command (pwd=%s): %s",
        builder.directory(),
        getDescription(context));

    // Start the process.
    Process process = builder.start();

    // We buffer error messages in memory, as these are typically small.
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    // Open the temp file to write the intermediate output to and also fire up managed threads
    // to process the stdout and stderr lines from the preprocess command.
    int exitCode;
    try {
      try (FunctionLineProcessorThread errorProcessor =
               new FunctionLineProcessorThread(
                   process.getErrorStream(),
                   error,
                   createErrorLineProcessor(context.getProjectDirectoryRoot()))) {
        errorProcessor.start();

        // If we're preprocessing, we pipe the output through a processor to sanitize the line
        // markers.  So fire that up...
        if (operation == Operation.PREPROCESS) {
          try (OutputStream output =
                   context.getProjectFilesystem().newFileOutputStream(this.output);
               FunctionLineProcessorThread outputProcessor =
                   new FunctionLineProcessorThread(
                       process.getInputStream(),
                       output,
                       createPreprocessOutputLineProcessor(context.getProjectDirectoryRoot()))) {
            outputProcessor.start();
          }
        }
      }
      exitCode = process.waitFor();
    } finally {
      process.destroy();
      process.waitFor();
    }

    // If we generated any error output, print that to the console.
    String err = new String(error.toByteArray());
    if (!err.isEmpty()) {
      context.getConsole().printErrorText(err);
    }

    return exitCode;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    try {
      LOG.debug("%s %s -> %s", operation.toString().toLowerCase(), input, output);

      // We need completely different logic if we're piping from the preprocessor to the compiler.
      int exitCode;
      if (operation == Operation.PIPED_PREPROCESS_AND_COMPILE) {
        exitCode = executePiped(context);
      } else {
        exitCode = executeOther(context);
      }

      // If the compilation completed successfully and we didn't effect debug-info normalization
      // through #line directive modification, perform the in-place update of the compilation per
      // above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (exitCode == 0 && operation == Operation.COMPILE_MUNGE_DEBUGINFO) {
        try {
          sanitizer.restoreCompilationDirectory(
              context.getProjectDirectoryRoot().toAbsolutePath().resolve(output),
              context.getProjectDirectoryRoot().toAbsolutePath());
        } catch (IOException e) {
          context.logError(e, "error updating compilation directory");
          return 1;
        }
      }

      if (exitCode != 0) {
        LOG.warn("error %d %s %s", exitCode, operation.toString().toLowerCase(), input);
      }

      return exitCode;

    } catch (Exception e) {
      MoreThrowables.propagateIfInterrupt(e);
      context.getConsole().printBuildFailureWithStacktrace(e);
      return 1;
    }
  }

  public ImmutableList<String> getCommand() {
    switch (operation) {
      case COMPILE:
      case COMPILE_MUNGE_DEBUGINFO:
        return makeCompileCommand(
            input.toString(),
            inputType.getLanguage());
      case PREPROCESS:
        return makePreprocessCommand();
      // $CASES-OMITTED$
      default:
        throw new RuntimeException("invalid operation type");
    }
  }

  public String getDescriptionNoContext() {
    switch (operation) {
      case PIPED_PREPROCESS_AND_COMPILE: {
        return Joiner.on(' ').join(
            FluentIterable.from(makePreprocessCommand())
            .transform(Escaper.SHELL_ESCAPER)) +
            " | " +
            Joiner.on(' ').join(
                FluentIterable.from(
                    makeCompileCommand(
                        "-",
                        inputType.getPreprocessedLanguage()))
                .transform(Escaper.SHELL_ESCAPER));

      }
      // $CASES-OMITTED$
      default: {
        return Joiner.on(' ').join(
            FluentIterable.from(getCommand())
            .transform(Escaper.SHELL_ESCAPER));
      }
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getDescriptionNoContext();
  }

  public enum Operation {
    COMPILE,
    COMPILE_MUNGE_DEBUGINFO,
    PREPROCESS,
    PIPED_PREPROCESS_AND_COMPILE,
    ;

    public boolean isPreprocess() {
      return this == COMPILE_MUNGE_DEBUGINFO ||
          this == PREPROCESS ||
          this == PIPED_PREPROCESS_AND_COMPILE;
    }

    public boolean isCompile() {
      return this == COMPILE ||
          this == COMPILE_MUNGE_DEBUGINFO ||
          this == PIPED_PREPROCESS_AND_COMPILE;
    }

  }
}
