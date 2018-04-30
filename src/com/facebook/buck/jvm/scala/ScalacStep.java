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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

public class ScalacStep extends ShellStep {
  private final Tool scalac;
  private final ImmutableList<String> extraArguments;
  private final SourcePathResolver resolver;
  private final Path outputDirectory;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final ImmutableSortedSet<Path> classpathEntries;
  private final ProjectFilesystem filesystem;

  ScalacStep(
      BuildTarget buildTarget,
      Tool scalac,
      ImmutableList<String> extraArguments,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> classpathEntries,
      ProjectFilesystem filesystem) {
    super(Optional.of(buildTarget), filesystem.getRootPath());

    this.scalac = scalac;
    this.extraArguments = extraArguments;
    this.resolver = resolver;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.classpathEntries = classpathEntries;
    this.filesystem = filesystem;
  }

  @Override
  public String getShortName() {
    return "scalac";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder =
        ImmutableList.<String>builder()
            .addAll(scalac.getCommandPrefix(resolver))
            .addAll(extraArguments);

    Verbosity verbosity = context.getVerbosity();
    if (verbosity.shouldUseVerbosityFlagIfAvailable()) {
      commandBuilder.add("-verbose");
    }

    // Specify the output directory.
    commandBuilder.add("-d").add(filesystem.resolve(outputDirectory).toString());

    String classpath =
        classpathEntries
            .stream()
            .map(filesystem::resolve)
            .map(Path::toString)
            .collect(Collectors.joining(File.pathSeparator));
    if (classpath.isEmpty()) {
      commandBuilder.add("-classpath", "''");
    } else {
      commandBuilder.add("-classpath", classpath);
    }
    commandBuilder.addAll(sourceFilePaths.stream().map(Object::toString).iterator());

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return scalac.getEnvironment(resolver);
  }
}
