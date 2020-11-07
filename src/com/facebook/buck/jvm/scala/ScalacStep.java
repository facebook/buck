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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class ScalacStep extends IsolatedShellStep {

  private final ImmutableList<String> commandPrefix;
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> extraArguments;
  private final Path outputDirectory;
  private final ImmutableSortedSet<Path> sourceFilePaths;
  private final ImmutableSortedSet<Path> classpathEntries;

  ScalacStep(
      ImmutableList<String> commandPrefix,
      ImmutableMap<String, String> environment,
      ImmutableList<String> extraArguments,
      Path outputDirectory,
      ImmutableSortedSet<Path> sourceFilePaths,
      ImmutableSortedSet<Path> classpathEntries,
      AbsPath ruleCellRoot,
      RelPath cellPath,
      boolean withDownwardApi) {
    super(ruleCellRoot, cellPath, withDownwardApi);
    this.commandPrefix = commandPrefix;
    this.environment = environment;
    this.extraArguments = extraArguments;
    this.outputDirectory = outputDirectory;
    this.sourceFilePaths = sourceFilePaths;
    this.classpathEntries = classpathEntries;
  }

  @Override
  public String getShortName() {
    return "scalac";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    AbsPath ruleCellRoot = context.getRuleCellRoot();

    ImmutableList.Builder<String> commandBuilder =
        ImmutableList.<String>builder().addAll(commandPrefix).addAll(extraArguments);

    Verbosity verbosity = context.getVerbosity();
    if (verbosity.shouldUseVerbosityFlagIfAvailable()) {
      commandBuilder.add("-verbose");
    }

    // Specify the output directory.
    commandBuilder.add("-d").add(ruleCellRoot.resolve(outputDirectory).toString());

    String classpath =
        classpathEntries.stream()
            .map(ruleCellRoot::resolve)
            .map(AbsPath::toString)
            .collect(Collectors.joining(File.pathSeparator));
    if (classpath.isEmpty()) {
      commandBuilder.add("-classpath", "''");
    } else {
      commandBuilder.add("-classpath", classpath);
    }
    commandBuilder.addAll(sourceFilePaths.stream().map(Path::toString).iterator());

    return commandBuilder.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(Platform platform) {
    return environment;
  }
}
