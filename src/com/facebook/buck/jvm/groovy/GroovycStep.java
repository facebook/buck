/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.groovy;

import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.OptionsConsumer;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
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
import java.util.Optional;

class GroovycStep implements Step {
  private final Tool groovyc;
  private final Optional<ImmutableList<String>> extraArguments;
  private final JavacOptions javacOptions;
  private final SourcePathResolver resolver;
  private final Path outputDirectory;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final Path pathToSrcsList;
  private final ImmutableSortedSet<Path> declaredClasspathEntries;
  private final ProjectFilesystem filesystem;

  GroovycStep(
      Tool groovyc,
      Optional<ImmutableList<String>> extraArguments,
      JavacOptions javacOptions,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      ProjectFilesystem filesystem) {
    this.groovyc = groovyc;
    this.extraArguments = extraArguments;
    this.javacOptions = javacOptions;
    this.resolver = resolver;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.filesystem = filesystem;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(createCommand())
            .setEnvironment(context.getEnvironment())
            .setDirectory(filesystem.getRootPath().toAbsolutePath())
            .build();
    writePathToSourcesList(sourceFilePaths);
    ProcessExecutor processExecutor = context.getProcessExecutor();
    return StepExecutionResult.of(processExecutor.launchAndExecute(params));
  }

  @Override
  public String getShortName() {
    return Joiner.on(" ").join(groovyc.getCommandPrefix(resolver));
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(createCommand());
  }

  private ImmutableList<String> createCommand() {
    ImmutableList.Builder<String> command = ImmutableList.builder();

    command.addAll(groovyc.getCommandPrefix(resolver));

    String classpath =
        Joiner.on(File.pathSeparator).join(transform(declaredClasspathEntries, Object::toString));
    command
        .add("-cp")
        .add(classpath.isEmpty() ? "''" : classpath)
        .add("-d")
        .add(outputDirectory.toString());
    addCrossCompilationOptions(command);

    command.addAll(extraArguments.orElse(ImmutableList.of()));

    command.add("@" + pathToSrcsList);

    return command.build();
  }

  private void writePathToSourcesList(Iterable<Path> expandedSources) throws IOException {
    filesystem.writeLinesToPath(
        FluentIterable.from(expandedSources)
            .transform(Object::toString)
            .transform(Javac.ARGFILES_ESCAPER::apply),
        pathToSrcsList);
  }

  private void addCrossCompilationOptions(ImmutableList.Builder<String> command) {
    if (shouldCrossCompile()) {
      command.add("-j");
      javacOptions.appendOptionsTo(
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
          resolver,
          filesystem);
    }
  }

  private boolean shouldCrossCompile() {
    return any(sourceFilePaths, input -> input.toString().endsWith(".java"));
  }
}
