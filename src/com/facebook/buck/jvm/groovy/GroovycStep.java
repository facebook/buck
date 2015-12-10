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

import static com.google.common.base.Functions.toStringFunction;
import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

class GroovycStep implements Step {
  private final Tool groovyc;
  private final Optional<ImmutableList<String>> extraArguments;
  private final SourcePathResolver resolver;
  private final Path outputDirectory;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final ImmutableSortedSet<Path> declaredClasspathEntries;
  private final ProjectFilesystem filesystem;

  GroovycStep(
      Tool groovyc,
      Optional<ImmutableList<String>> extraArguments,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      ProjectFilesystem filesystem) {
    this.groovyc = groovyc;
    this.extraArguments = extraArguments;
    this.resolver = resolver;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.filesystem = filesystem;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {

    ProcessBuilder processBuilder = new ProcessBuilder(createCommand());

    Map<String, String> env = processBuilder.environment();
    env.clear();
    env.putAll(context.getEnvironment());

    processBuilder.directory(filesystem.getRootPath().toAbsolutePath().toFile());
    int exitCode = -1;
    try {
      exitCode = context.getProcessExecutor().execute(processBuilder.start()).getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
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
    command.addAll(groovyc.getCommandPrefix(resolver))
        .add("-cp")
        .add(Joiner.on(":").join(transform(declaredClasspathEntries, toStringFunction())))
        .add("-d")
        .add(outputDirectory.toString())
        .addAll(extraArguments.or(ImmutableList.<String>of()))
        .addAll(transform(sourceFilePaths, toStringFunction()));
    return command.build();
  }
}
