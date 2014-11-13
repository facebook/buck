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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.FunctionLineProcessorThread;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;

/**
 * A step that compiles and assembles C/C++ sources.
 */
public class CxxCompileStep implements Step {

  private final Path compiler;
  private final ImmutableList<String> flags;
  private final Path output;
  private final Path input;
  private final Optional<DebugPathSanitizer> sanitizer;

  public CxxCompileStep(
      Path compiler,
      ImmutableList<String> flags,
      Path output,
      Path input,
      Optional<DebugPathSanitizer> sanitizer) {
    this.compiler = compiler;
    this.flags = flags;
    this.output = output;
    this.input = input;
    this.sanitizer = sanitizer;
  }

  @VisibleForTesting
  protected Function<String, String> createErrorLineProcessor(final Path workingDir) {
    return CxxDescriptionEnhancer.createErrorMessagePathProcessor(
        new Function<String, String>() {
          @Override
          public String apply(String original) {
            return sanitizer.isPresent() ?
                sanitizer.get().restore(Optional.of(workingDir), original) :
                original;
          }
        });
  }

  @Override
  public String getShortName() {
    return "c++ compile";
  }

  @VisibleForTesting
  protected ImmutableList<String> getCommand() {
    return ImmutableList.<String>builder()
        .add(compiler.toString())
        .add("-c")
        .addAll(flags)
        .add("-o", output.toString())
        .add(input.toString())
        .build();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(getCommand());
    builder.directory(context.getProjectDirectoryRoot().toAbsolutePath().toFile());
    builder.redirectError(ProcessBuilder.Redirect.PIPE);

    // A forced compilation directory is set in the constructor.  Now, we can't actually
    // force the compiler to embed this into the binary -- all we can do set the PWD environment
    // to variations of the actual current working directory (e.g. /actual/dir or /actual/dir////).
    // So we use this knob to expand the space used to store the compilation directory to the
    // size we need for the compilation directory we really want, then do an in-place
    // find-and-replace to update the compilation directory after the fact.
    if (sanitizer.isPresent()) {
      builder.environment().put(
          "PWD",
          sanitizer.get().getExpandedPath(context.getProjectDirectoryRoot().toAbsolutePath()));
    }

    try {

      // Start the process.
      Process process = builder.start();

      // We buffer error messages in memory, as these are typically small.
      ByteArrayOutputStream error = new ByteArrayOutputStream();

      // Open the temp file to write the intermediate output to and also fire up managed threads
      // to process the stdout and stderr lines from the preprocess command.
      try (FunctionLineProcessorThread errorProcessor =
               new FunctionLineProcessorThread(
                   process.getErrorStream(),
                   error,
                   createErrorLineProcessor(context.getProjectDirectoryRoot().toAbsolutePath()))) {
        errorProcessor.start();
      }

      // Wait for the process to finish, and grab it's exit code.
      int exitCode = process.waitFor();

      // If we generated any error output, print that to the console.
      String err = new String(error.toByteArray());
      if (!err.isEmpty()) {
        context.getConsole().printErrorText(err);
      }

      // If the compilation completed successfully, perform the in-place update of the compilation
      // as per above.  This locates the relevant debug section and swaps out the expanded actual
      // compilation directory with the one we really want.
      if (exitCode == 0 && sanitizer.isPresent()) {
        try {
          sanitizer.get().restoreCompilationDirectory(
              context.getProjectDirectoryRoot().toAbsolutePath().resolve(output),
              context.getProjectDirectoryRoot().toAbsolutePath());
        } catch (IOException e) {
          context.logError(e, "error updating compilation directory");
          return 1;
        }
      }

      return exitCode;

    } catch (InterruptedException | InterruptedIOException e) {
      throw new InterruptedException();

    } catch (Exception e) {
      context.getConsole().printBuildFailureWithStacktrace(e);
      return 1;
    }
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(
        FluentIterable.from(getCommand())
            .transform(Escaper.BASH_ESCAPER));
  }

}
