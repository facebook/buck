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
import com.facebook.buck.util.LineProcessorThread;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A step that preprocesses C/C++ sources.
 */
public class CxxPreprocessStep implements Step {

  private final Path preprocessor;
  private final ImmutableList<String> flags;
  private final Path output;
  private final Path input;
  private final ImmutableList<Path> includes;
  private final ImmutableList<Path> systemIncludes;
  private final ImmutableMap<Path, Path> replacementPaths;

  public CxxPreprocessStep(
      Path preprocessor,
      ImmutableList<String> flags,
      Path output,
      Path input,
      ImmutableList<Path> includes,
      ImmutableList<Path> systemIncludes,
      ImmutableMap<Path, Path> replacementPaths) {
    this.preprocessor = preprocessor;
    this.flags = flags;
    this.output = output;
    this.input = input;
    this.includes = includes;
    this.systemIncludes = systemIncludes;
    this.replacementPaths = replacementPaths;
  }

  @Override
  public String getShortName() {
    return "c++ preprocess";
  }

  @VisibleForTesting
  protected ImmutableList<String> getCommand() {
    return ImmutableList.<String>builder()
        .add(preprocessor.toString())
        .add("-E")
        .addAll(flags)
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-I"),
                Iterables.transform(includes, Functions.toStringFunction())))
        .addAll(
            MoreIterables.zipAndConcat(
                Iterables.cycle("-isystem"),
                Iterables.transform(systemIncludes, Functions.toStringFunction())))
        .add(input.toString())
        .build();
  }

  @VisibleForTesting
  protected static Function<String, String> createOutputLineProcessor(
      final ImmutableMap<Path, Path> replacementPaths) {

    return new Function<String, String>() {

      private final Pattern lineMarkers =
          Pattern.compile("^# (?<num>\\d+) \"(?<path>[^\"]+)\"(?<rest>.*)?$");

      @Override
      public String apply(String line) {
        if (line.startsWith("# ")) {
          Matcher m = lineMarkers.matcher(line);
          if (m.find()) {
            Path path = Paths.get(m.group("path"));
            Path replacement = replacementPaths.get(path);
            if (replacement != null) {
              String num = m.group("num");
              String rest = m.group("rest");
              return "# " + num + " \"" + replacement + "\"" + rest;
            }
          }
        }
        return line;
      }

    };
  }


  @VisibleForTesting
  protected static Function<String, String> createErrorLineProcessor(
      final ImmutableMap<Path, Path> replacementPaths) {

    return new Function<String, String>() {

      private final ImmutableList<Pattern> patterns =
          ImmutableList.of(
              Pattern.compile(
                  "(?<=^(?:In file included |\\s+)from )" +
                  "(?<path>[^:]+)" +
                  "(?=[:,](?:\\d+[:,](?:\\d+[:,])?)?$)"),
              Pattern.compile(
                  "^(?<path>[^:]+)(?=:(?:\\d+:(?:\\d+:)?)? )"));

      @Override
      public String apply(String line) {
        for (Pattern pattern : patterns) {
          Matcher m = pattern.matcher(line);
          if (m.find()) {
            Path path = Paths.get(m.group("path"));
            Path replacement = replacementPaths.get(path);
            if (replacement != null) {
              return m.replaceAll(replacement.toString());
            }
          }
        }
        return line;
      }

    };
  }

  @Override
  @SuppressWarnings("PMD.EmptyTryBlock")
  public int execute(ExecutionContext context) throws InterruptedException {
    ProcessBuilder builder = new ProcessBuilder();
    builder.command(getCommand());
    builder.directory(context.getProjectDirectoryRoot().toAbsolutePath().toFile());
    builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
    builder.redirectError(ProcessBuilder.Redirect.PIPE);

    Path outputPath = context.getProjectFilesystem().resolve(this.output);
    Path outputTempPath = context.getProjectFilesystem().resolve(this.output + ".tmp");

    try {

      // Start the process.
      Process process = builder.start();

      // We buffer error messages in memory, as these are typically small.
      ByteArrayOutputStream error = new ByteArrayOutputStream();

      // Open the temp file to write the intermediate output to and also fire up managed threads
      // to process the stdout and stderr lines from the preprocess command.
      try (OutputStream output = Files.newOutputStream(outputTempPath);
           FunctionProcessor outputProcessor =
               new FunctionProcessor(
                   process.getInputStream(),
                   output,
                   createOutputLineProcessor(replacementPaths));
           FunctionProcessor errorProcessor =
               new FunctionProcessor(
                   process.getErrorStream(),
                   error,
                   createErrorLineProcessor(replacementPaths))) {
        outputProcessor.start();
        errorProcessor.start();
      }

      // Wait for the process to finish, and grab it's exit code.
      int exitCode = process.waitFor();

      // If the process finished successfully, move the preprocessed output into it's final place.
      if (exitCode == 0) {
        Files.move(
            outputTempPath,
            outputPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      }

      // If we generated any error output, print that to the console.
      String err = new String(error.toByteArray());
      if (!err.isEmpty()) {
        context.getConsole().printErrorText(err);
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

  private static class FunctionProcessor extends LineProcessorThread {

    private final Function<String, String> processor;

    private FunctionProcessor(
        InputStream inputStream,
        OutputStream outputStream,
        Function<String, String> processor) {
      super(inputStream, outputStream);
      this.processor = Preconditions.checkNotNull(processor);
    }

    @Override
    public String process(String line) {
      return processor.apply(line);
    }

  }

}
