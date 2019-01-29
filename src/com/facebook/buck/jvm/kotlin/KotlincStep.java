/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class KotlincStep implements Step {

  private static final String CLASSPATH_FLAG = "-classpath";
  private static final String DESTINATION_FLAG = "-d";
  private static final String INCLUDE_RUNTIME_FLAG = "-include-runtime";
  private static final String EXCLUDE_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private final Kotlinc kotlinc;
  private final ImmutableSortedSet<Path> combinedClassPathEntries;
  private final Path outputDirectory;
  private final ImmutableList<String> extraArguments;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final ProjectFilesystem filesystem;
  private final Path pathToSrcsList;
  private final BuildTarget invokingRule;
  private final Optional<Path> workingDirectory;

  KotlincStep(
      BuildTarget invokingRule,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<Path> combinedClassPathEntries,
      Kotlinc kotlinc,
      ImmutableList<String> extraArguments,
      ProjectFilesystem filesystem,
      Optional<Path> workingDirectory) {
    this.invokingRule = invokingRule;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.kotlinc = kotlinc;
    this.combinedClassPathEntries = combinedClassPathEntries;
    this.extraArguments = extraArguments;
    this.filesystem = filesystem;
    this.workingDirectory = workingDirectory;
  }

  @Override
  public String getShortName() {
    return getKotlinc().getShortName();
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    Verbosity verbosity =
        context.getVerbosity().isSilent() ? Verbosity.STANDARD_INFORMATION : context.getVerbosity();

    try (CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        ExecutionContext firstOrderContext =
            context.createSubContext(stdout, stderr, Optional.of(verbosity))) {

      int declaredDepsBuildResult =
          kotlinc.buildWithClasspath(
              firstOrderContext,
              invokingRule,
              getOptions(context, combinedClassPathEntries),
              sourceFilePaths,
              pathToSrcsList,
              workingDirectory,
              filesystem);

      String firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);
      Optional<String> returnedStderr;
      if (declaredDepsBuildResult != 0) {
        returnedStderr = Optional.of(firstOrderStderr);
      } else {
        returnedStderr = Optional.empty();
      }
      return StepExecutionResult.of(declaredDepsBuildResult, returnedStderr);
    }
  }

  @VisibleForTesting
  Kotlinc getKotlinc() {
    return kotlinc;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getKotlinc()
        .getDescription(
            getOptions(context, getClasspathEntries()), sourceFilePaths, pathToSrcsList);
  }

  /**
   * Returns a list of command-line options to pass to javac. These options reflect the
   * configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context, ImmutableSortedSet<Path> buildClasspathEntries) {
    return getOptions(filesystem, outputDirectory, buildClasspathEntries);
  }

  private ImmutableList<String> getOptions(
      ProjectFilesystem filesystem,
      Path outputDirectory,
      ImmutableSortedSet<Path> buildClasspathEntries) {

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    if (outputDirectory != null) {
      builder.add(DESTINATION_FLAG, filesystem.resolve(outputDirectory).toString());
    }

    if (!buildClasspathEntries.isEmpty()) {
      builder.add(
          CLASSPATH_FLAG,
          Joiner.on(File.pathSeparator)
              .join(
                  transform(
                      buildClasspathEntries,
                      path -> filesystem.resolve(path).toAbsolutePath().toString())));
    }

    builder.add(INCLUDE_RUNTIME_FLAG);
    builder.add(EXCLUDE_REFLECT);
    builder.add(VERBOSE);

    if (!extraArguments.isEmpty()) {
      builder.addAll(extraArguments);
    }

    return builder.build();
  }

  /** @return The classpath entries used to invoke javac. */
  @VisibleForTesting
  ImmutableSortedSet<Path> getClasspathEntries() {
    return combinedClassPathEntries;
  }

  @VisibleForTesting
  ImmutableSortedSet<Path> getSrcs() {
    return sourceFilePaths;
  }
}
