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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

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
  private final BuildTarget invokingRule;
  private final ProjectFilesystem filesystem;

  GroovycStep(
      Tool groovyc,
      Optional<ImmutableList<String>> extraArguments,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      BuildTarget invokingRule,
      ProjectFilesystem filesystem) {
    this.groovyc = groovyc;
    this.extraArguments = extraArguments;
    this.resolver = resolver;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.invokingRule = invokingRule;
    this.filesystem = filesystem;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    ImmutableList.Builder<String> command = createCommandList();

    ProcessBuilder processBuilder = new ProcessBuilder(command.build());

    // Set environment to client environment and add additional information.
    Map<String, String> env = processBuilder.environment();
    env.clear();
    env.putAll(context.getEnvironment());
    env.put("BUCK_INVOKING_RULE", invokingRule.toString());
    env.put("BUCK_TARGET", invokingRule.toString());
    env.put("BUCK_DIRECTORY_ROOT", filesystem.getRootPath().toAbsolutePath().toString());

    processBuilder.directory(filesystem.getRootPath().toAbsolutePath().toFile());
    // Run the command
    int exitCode = -1;
    try {
      exitCode = context.getProcessExecutor().execute(processBuilder.start()).getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
  }

  private ImmutableList.Builder<String> createCommandList() {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(groovyc.getCommandPrefix(resolver))
        .add("-cp")
        .add(Joiner.on(":").join(Iterables.transform(
            declaredClasspathEntries,
            new Function<Path, String>() {
              @Override
              public String apply(Path input) {
                return input.toString();
              }
            })))
        .add("-d")
        .add(outputDirectory.toString())
        .addAll(extraArguments.or(ImmutableList.<String>of()))
        .addAll(Iterables.transform(
            sourceFilePaths,
            new Function<Path, String>() {
              @Override
              public String apply(Path input) {
                return input.toString();
              }
            }));
    return command;
  }

  @Override
  public String getShortName() {
    return Joiner.on(" ").join(groovyc.getCommandPrefix(resolver));
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ").join(createCommandList().build());
  }
}
