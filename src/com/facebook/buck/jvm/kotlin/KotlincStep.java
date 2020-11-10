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

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/** Kotlin compile Step */
public class KotlincStep extends IsolatedStep {

  private static final String CLASSPATH_FLAG = "-classpath";
  private static final String DESTINATION_FLAG = "-d";
  private static final String INCLUDE_RUNTIME_FLAG = "-include-runtime";
  private static final String EXCLUDE_REFLECT = "-no-reflect";
  private static final String VERBOSE = "-verbose";

  private final Kotlinc kotlinc;
  private final ImmutableSortedSet<Path> combinedClassPathEntries;
  private final Path outputDirectory;
  private final ImmutableList<String> extraArguments;
  private final ImmutableList<String> verboseModeOnlyExtraArguments;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final Path pathToSrcsList;
  private final BuildTarget invokingRule;
  private final Optional<Path> workingDirectory;
  private final boolean withDownwardApi;

  KotlincStep(
      BuildTarget invokingRule,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<Path> combinedClassPathEntries,
      Kotlinc kotlinc,
      ImmutableList<String> extraArguments,
      ImmutableList<String> verboseModeOnlyExtraArguments,
      Optional<Path> workingDirectory,
      boolean withDownwardApi) {
    this.invokingRule = invokingRule;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.kotlinc = kotlinc;
    this.combinedClassPathEntries = combinedClassPathEntries;
    this.extraArguments = extraArguments;
    this.verboseModeOnlyExtraArguments = verboseModeOnlyExtraArguments;
    this.workingDirectory = workingDirectory;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public String getShortName() {
    return getKotlinc().getShortName();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    Verbosity verbosity =
        context.getVerbosity().isSilent() ? Verbosity.STANDARD_INFORMATION : context.getVerbosity();

    try (CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        IsolatedExecutionContext firstOrderContext =
            context.createSubContext(stdout, stderr, Optional.of(verbosity))) {

      int declaredDepsBuildResult =
          kotlinc.buildWithClasspath(
              firstOrderContext,
              invokingRule,
              getOptions(context, combinedClassPathEntries),
              sourceFilePaths,
              pathToSrcsList,
              workingDirectory,
              context.getRuleCellRoot(),
              withDownwardApi);

      String firstOrderStderr = stderr.getContentsAsString(StandardCharsets.UTF_8);
      Optional<String> returnedStderr;
      if (declaredDepsBuildResult != StepExecutionResults.SUCCESS_EXIT_CODE) {
        returnedStderr = Optional.of(firstOrderStderr);
      } else {
        returnedStderr = Optional.empty();
      }
      return StepExecutionResult.builder()
          .setExitCode(declaredDepsBuildResult)
          .setStderr(returnedStderr)
          .build();
    }
  }

  @VisibleForTesting
  Kotlinc getKotlinc() {
    return kotlinc;
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
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
      IsolatedExecutionContext context, ImmutableSortedSet<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    AbsPath ruleCellRoot = context.getRuleCellRoot();

    if (outputDirectory != null) {
      builder.add(DESTINATION_FLAG, ruleCellRoot.resolve(outputDirectory).toString());
    }

    if (!buildClasspathEntries.isEmpty()) {
      builder.add(
          CLASSPATH_FLAG,
          Joiner.on(File.pathSeparator)
              .join(
                  transform(buildClasspathEntries, path -> ruleCellRoot.resolve(path).toString())));
    }

    builder.add(INCLUDE_RUNTIME_FLAG);
    builder.add(EXCLUDE_REFLECT);
    builder.add(VERBOSE);

    if (!extraArguments.isEmpty()) {
      for (String extraArgument : extraArguments) {
        if (!extraArgument.isEmpty()) {
          builder.add(extraArgument);
        }
      }
    }

    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()
        && !verboseModeOnlyExtraArguments.isEmpty()) {
      for (String extraArgument : verboseModeOnlyExtraArguments) {
        if (!extraArgument.isEmpty()) {
          builder.add(extraArgument);
        }
      }
    }

    return builder.build();
  }

  /** @return The classpath entries used to invoke javac. */
  @VisibleForTesting
  ImmutableSortedSet<Path> getClasspathEntries() {
    return combinedClassPathEntries;
  }
}
