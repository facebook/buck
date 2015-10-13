/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.groovy;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command used to compile groovy libraries
 */
public class GroovycStep implements Step {

  private final Path outputDirectory;

  private final Optional<Path> workingDirectory;

  private final ImmutableSet<Path> groovySourceFilePaths;

  private final Optional<Path> pathToSrcsList;

  private final ImmutableSet<Path> declaredClasspathEntries;

  private final BuildTarget invokingRule;

  private final ProjectFilesystem filesystem;

  private AtomicBoolean isExecuted = new AtomicBoolean(false);

  public GroovycStep(
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Set<Path> groovySourceFilePaths,
      Optional<Path> pathToSrcsList,
      Set<Path> declaredClasspathEntries,
      BuildTarget invokingRule,
      ProjectFilesystem filesystem) {
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.groovySourceFilePaths = ImmutableSet.copyOf(groovySourceFilePaths);
    this.pathToSrcsList = pathToSrcsList;

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = invokingRule;
    this.filesystem = filesystem;
  }

  @Override
  public final int execute(ExecutionContext context) throws IOException, InterruptedException {
    try {
      return tryBuildWithFirstOrderDeps(context, filesystem);
    } finally {
      isExecuted.set(true);
    }
  }

  private int tryBuildWithFirstOrderDeps(ExecutionContext context, ProjectFilesystem filesystem)
      throws InterruptedException, IOException {
    try (
        CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        ExecutionContext firstOrderContext = context.createSubContext(stdout, stderr)) {

      Groovyc groovyc = getGroovyc();

      int declaredDepsResult = groovyc.buildWithClasspath(
          firstOrderContext,
          filesystem,
          invokingRule,
          getOptions(context, declaredClasspathEntries),
          groovySourceFilePaths,
          pathToSrcsList,
          workingDirectory);

      String firstOrderStdout = stdout.getContentsAsString(Charsets.UTF_8);
      String firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);

      if (declaredDepsResult != 0) {
        context.getStdOut().print(firstOrderStdout);
        context.getStdErr().print(firstOrderStderr);
      }

      return declaredDepsResult;
    }
  }

  private Groovyc getGroovyc() {
    return new Groovyc(Paths.get(System.getenv("DEPENDENCY_DIR"), "groovy/bin/groovyc"));
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getGroovyc().getDescription(
        getOptions(context, getClasspathEntries()),
        groovySourceFilePaths,
        pathToSrcsList);
  }

  @Override
  public String getShortName() {
    return getGroovyc().getShortName();
  }

  /**
   * Returns a list of command-line options to pass to groovyc.  These options reflect
   * the configuration of this groovyc command.
   *
   * @param context the ExecutionContext with in which groovyc will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context,
      Set<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    // Specify the output directory.
    Function<Path, Path> pathRelativizer = filesystem.getAbsolutifier();
    builder.add("-d").add(pathRelativizer.apply(outputDirectory).toString());

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(
          FluentIterable.from(buildClasspathEntries)
              .transform(pathRelativizer));
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
    }

    return builder.build();
  }

  /**
   * @return The classpath entries used to invoke javac.
   */
  @VisibleForTesting
  ImmutableSet<Path> getClasspathEntries() {
    return declaredClasspathEntries;
  }

}
