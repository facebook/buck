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
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A step that preprocesses and/or compiles C/C++ sources in a single step.
 */
public class CxxPreprocessAndCompileStep implements Step {

  private static final Logger LOG = Logger.get(CxxPreprocessAndCompileStep.class);

  private final Operation operation;
  private final Path output;
  private final Path input;
  private final ImmutableList<String> command;
  private final ImmutableMap<Path, Path> replacementPaths;
  private final Optional<DebugPathSanitizer> sanitizer;

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
      ImmutableList<String> command,
      ImmutableMap<Path, Path> replacementPaths,
      Optional<DebugPathSanitizer> sanitizer) {
    this.operation = operation;
    this.output = output;
    this.input = input;
    this.command = command;
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
  Function<String, String> createPreprocessOutputLineProcessor(final Path workingDir) {
    return new Function<String, String>() {

      private final Pattern lineMarkers =
          Pattern.compile("^# (?<num>\\d+) \"(?<path>[^\"]+)\"(?<rest>.*)?$");

      @Override
      public String apply(String line) {
        if (line.startsWith("# ")) {
          Matcher m = lineMarkers.matcher(line);

          if (m.find() && !SPECIAL_INCLUDE_PATHS.contains(m.group("path"))) {
            String originalPath = m.group("path");
            String replacementPath = originalPath;

            replacementPath = Optional
                .fromNullable(replacementPaths.get(Paths.get(replacementPath)))
                .transform(Functions.toStringFunction())
                .or(replacementPath);

            if (sanitizer.isPresent()) {
              replacementPath = sanitizer.get().sanitize(Optional.of(workingDir), replacementPath);
            }

            if (!originalPath.equals(replacementPath)) {
              String num = m.group("num");
              String rest = m.group("rest");
              return "# " + num + " \"" + replacementPath + "\"" + rest;
            }
          }
        }
        return line;
      }
    };
  }

  @VisibleForTesting
  Function<String, String> createErrorLineProcessor(final Path workingDir) {
    return CxxDescriptionEnhancer.createErrorMessagePathProcessor(
        new Function<String, String>() {
          @Override
          public String apply(String original) {
            Path path = Paths.get(original);

            // If we're compiling, we also need to restore the original working directory in the
            // error output.
            if (operation == Operation.COMPILE) {
              path =
                  sanitizer.isPresent() ?
                      Paths.get(sanitizer.get().restore(Optional.of(workingDir), original)) :
                      path;
            }

            // And, of course, we need to fixup any replacement paths.
            path = Optional.fromNullable(replacementPaths.get(path)).or(path);

            return path.toString();
          }
        });
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    LOG.debug("%s %s -> %s", operation.toString().toLowerCase(), input, output);

    ProcessBuilder builder = new ProcessBuilder();
    builder.command(command);
    builder.directory(context.getProjectDirectoryRoot().toAbsolutePath().toFile());
    builder.redirectError(ProcessBuilder.Redirect.PIPE);

    // If we're preprocessing, file output goes through stdout, so we can postprocess it.
    if (operation == Operation.PREPROCESS) {
      builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
    }

    // A forced compilation directory is set in the constructor.  Now, we can't actually
    // force the compiler to embed this into the binary -- all we can do set the PWD environment
    // to variations of the actual current working directory (e.g. /actual/dir or /actual/dir////).
    // So we use this knob to expand the space used to store the compilation directory to the
    // size we need for the compilation directory we really want, then do an in-place
    // find-and-replace to update the compilation directory after the fact.
    if (operation == Operation.COMPILE && sanitizer.isPresent()) {
      builder.environment().put(
          "PWD",
          sanitizer.get().getExpandedPath(context.getProjectDirectoryRoot().toAbsolutePath()));
    }

    try {
      LOG.debug(
          "Running command (pwd=%s): %s",
          builder.directory(),
          Joiner.on(' ').join(Iterables.transform(builder.command(), Escaper.SHELL_ESCAPER)));

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

      // If the compilation completed successfully, perform the in-place update of the compilation
      // as per above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (exitCode == 0 && operation == Operation.COMPILE && sanitizer.isPresent()) {
        try {
          sanitizer.get().restoreCompilationDirectory(
              context.getProjectDirectoryRoot().toAbsolutePath().resolve(output),
              context.getProjectDirectoryRoot().toAbsolutePath());
        } catch (IOException e) {
          context.logError(e, "error updating compilation directory");
          return 1;
        }
      }

      if (exitCode != 0) {
        LOG.warn("error %d %s %s: %s", exitCode, operation.toString().toLowerCase(), input, err);
      }

      return exitCode;

    } catch (Exception e) {
      MoreThrowables.propagateIfInterrupt(e);
      context.getConsole().printBuildFailureWithStacktrace(e);
      return 1;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(
        FluentIterable.from(command)
            .transform(Escaper.SHELL_ESCAPER));
  }

  public static enum Operation {

    COMPILE("-c"),
    PREPROCESS("-E"),
    ;

    private final String flag;

    private Operation(String flag) {
      this.flag = flag;
    }

    public String getFlag() {
      return flag;
    }

  }

}
