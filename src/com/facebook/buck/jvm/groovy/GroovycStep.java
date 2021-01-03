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

package com.facebook.buck.jvm.groovy;

import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.OptionsConsumer;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.facebook.buck.jvm.java.ResolvedJavacOptions;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/** Groovy compile step. */
class GroovycStep extends IsolatedStep {

  private final ImmutableList<String> commandPrefix;
  private final Optional<ImmutableList<String>> extraArguments;
  private final ResolvedJavacOptions javacOptions;
  private final Path outputDirectory;
  private final ImmutableSortedSet<RelPath> sourceFilePaths;
  private final Path pathToSrcsList;
  private final ImmutableSortedSet<RelPath> declaredClasspathEntries;
  private final boolean withDownwardApi;

  GroovycStep(
      ImmutableList<String> commandPrefix,
      Optional<ImmutableList<String>> extraArguments,
      ResolvedJavacOptions javacOptions,
      Path outputDirectory,
      ImmutableSortedSet<RelPath> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<RelPath> declaredClasspathEntries,
      boolean withDownwardApi) {
    this.commandPrefix = commandPrefix;
    this.extraArguments = extraArguments;
    this.javacOptions = javacOptions;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.withDownwardApi = withDownwardApi;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    AbsPath ruleCellRoot = context.getRuleCellRoot();
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(createCommand(ruleCellRoot))
            .setEnvironment(context.getEnvironment())
            .setDirectory(ruleCellRoot.getPath())
            .build();
    writePathToSourcesList(context.getRuleCellRoot(), sourceFilePaths);
    ProcessExecutor processExecutor = context.getProcessExecutor();
    if (withDownwardApi) {
      processExecutor =
          processExecutor.withDownwardAPI(
              DownwardApiProcessExecutor.FACTORY, context.getIsolatedEventBus());
    }
    return StepExecutionResult.of(processExecutor.launchAndExecute(params));
  }

  @Override
  public String getShortName() {
    return Joiner.on(" ").join(commandPrefix);
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return Joiner.on(" ").join(createCommand(context.getRuleCellRoot()));
  }

  private ImmutableList<String> createCommand(AbsPath ruleCellRoot) {
    ImmutableList.Builder<String> command = ImmutableList.builder();

    command.addAll(commandPrefix);

    String classpath =
        Joiner.on(File.pathSeparator)
            .join(
                transform(
                    declaredClasspathEntries,
                    path -> Objects.toString(ruleCellRoot.resolve(path).normalize())));
    command
        .add("-cp")
        .add(classpath.isEmpty() ? "''" : classpath)
        .add("-d")
        .add(outputDirectory.toString());
    addCrossCompilationOptions(command, ruleCellRoot);

    command.addAll(extraArguments.orElse(ImmutableList.of()));

    command.add("@" + pathToSrcsList);

    return command.build();
  }

  private void writePathToSourcesList(AbsPath rootCellRoot, Iterable<RelPath> expandedSources)
      throws IOException {
    ProjectFilesystemUtils.writeLinesToPath(
        rootCellRoot,
        FluentIterable.from(expandedSources)
            .transform(Object::toString)
            .transform(ResolvedJavac.ARGFILES_ESCAPER::apply),
        pathToSrcsList);
  }

  private void addCrossCompilationOptions(
      ImmutableList.Builder<String> command, AbsPath ruleCellRoot) {
    if (shouldCrossCompile()) {
      command.add("-j");
      JavacOptions.appendOptionsTo(
          new OptionsConsumer() {
            @Override
            public void addOptionValue(String option, String value) {
              // Explicitly disallow the setting of sourcepath in a cross compilation context.
              // The implementation of `appendOptionsTo` provides a blank default, which
              // confuses the cross compilation step's javac (it won't find any class files
              // compiled by groovyc).
              if (option.equals("sourcepath")) {
                return;
              }
              if (!Strings.isNullOrEmpty(value)) {
                command.add("-J" + String.format("%s=%s", option, value));
              }
            }

            @Override
            public void addFlag(String flagName) {
              command.add("-F" + flagName);
            }

            @Override
            public void addExtras(Collection<String> extras) {
              for (String extra : extras) {
                if (extra.startsWith("-")) {
                  addFlag(extra.substring(1));
                } else {
                  addFlag(extra);
                }
              }
            }
          },
          javacOptions,
          ruleCellRoot);
    }
  }

  private boolean shouldCrossCompile() {
    return any(sourceFilePaths, input -> input.toString().endsWith(".java"));
  }
}
