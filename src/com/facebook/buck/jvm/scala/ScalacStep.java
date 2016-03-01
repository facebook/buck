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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.ExternalJavac;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class ScalacStep extends ShellStep {
  private final BuildTarget invokingRule;
  private final Tool scalac;
  private final ImmutableList<String> extraArguments;
  private final SourcePathResolver resolver;
  private final Path outputDirectory;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final ImmutableSortedSet<Path> classpathEntries;
  private final ProjectFilesystem filesystem;
  private final Path pathToSrcsList;

  ScalacStep(
      BuildTarget invokingRule,
      Tool scalac,
      ImmutableList<String> extraArguments,
      SourcePathResolver resolver,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> classpathEntries,
      ProjectFilesystem filesystem,
      Path pathToSrcsList) {
    super(filesystem.getRootPath());

    this.invokingRule = invokingRule;
    this.scalac = scalac;
    this.extraArguments = extraArguments;
    this.resolver = resolver;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.classpathEntries = classpathEntries;
    this.filesystem = filesystem;
    this.pathToSrcsList = pathToSrcsList;
  }

  @Override
  public String getShortName() {
    return "scalac";
  }


  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.<String>builder()
        .addAll(scalac.getCommandPrefix(resolver))
        .addAll(extraArguments);

    Verbosity verbosity = context.getVerbosity();
    if (verbosity.shouldUseVerbosityFlagIfAvailable()) {
      commandBuilder.add("-verbose");
    }

    // Specify the output directory.
    commandBuilder.add("-d").add(filesystem.resolve(outputDirectory).toString());

    String classpath = Joiner.on(File.pathSeparator).join(
        FluentIterable.from(classpathEntries)
            .transform(filesystem.getAbsolutifier()));
    if (classpath.isEmpty()) {
      commandBuilder.add("-classpath", "''");
    } else {
      commandBuilder.add("-classpath", classpath);
    }

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = ExternalJavac.getExpandedSourcePaths(
          filesystem,
          invokingRule,
          sourceFilePaths,
          Optional.of(workingDirectory),
          ".scala");
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule,
          workingDirectory);
    }

    try {
      filesystem.writeLinesToPath(
          FluentIterable.from(expandedSources)
              .transform(Functions.toStringFunction())
              .transform(ExternalJavac.ARGFILES_ESCAPER),
          pathToSrcsList);
      commandBuilder.add("@" + pathToSrcsList);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Cannot write list of .scala files to compile to %s file! Terminating compilation.",
          pathToSrcsList);
    }

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return scalac.getEnvironment(resolver);
  }
}
