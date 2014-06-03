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

package com.facebook.buck.java;


import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class ExternalJavacStep extends JavacStep {

  private final Path pathToJavac;
  private final BuildTarget target;
  private final Optional<Path> workingDirectory;

  public ExternalJavacStep(
      Path outputDirectory,
      Set<? extends SourcePath> javaSourceFilePaths,
      Set<Path> transitiveClasspathEntries,
      Set<Path> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> pathToOutputAbiFile,
      Optional<BuildTarget> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules,
      Optional<Path> pathToSrcsList,
      BuildTarget target,
      Optional<Path> workingDirectory) {
    super(outputDirectory,
        javaSourceFilePaths,
        transitiveClasspathEntries,
        declaredClasspathEntries,
        javacOptions,
        pathToOutputAbiFile,
        invokingRule,
        buildDependencies,
        suggestBuildRules,
        pathToSrcsList);
    this.pathToJavac = javacOptions.getJavaCompilerEnvironment().getJavacPath().get();
    this.target = Preconditions.checkNotNull(target);
    this.workingDirectory = Preconditions.checkNotNull(workingDirectory);
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder builder = new StringBuilder(pathToJavac.toString());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, getOptions(context, getClasspathEntries()));
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, javaSourceFilePaths);
    }

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return pathToJavac.toString();
  }

  @Override
  protected int buildWithClasspath(ExecutionContext context, Set<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.add(pathToJavac.toString());
    command.addAll(getOptions(context, buildClasspathEntries));

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = getExpandedSourcePaths(context);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          target,
          workingDirectory);
    }
    if (pathToSrcsList.isPresent()) {
      try {
        context.getProjectFilesystem().writeLinesToPath(
            Iterables.transform(expandedSources, Functions.toStringFunction()),
            pathToSrcsList.get());
        command.add("@" + pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(e,
            "Cannot write list of .java files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    } else {
      for (Path source : expandedSources) {
        command.add(source.toString());
      }
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command.build());

    // Set environment to client environment and add additional information.
    Map<String, String> env = processBuilder.environment();
    env.clear();
    env.putAll(context.getEnvironment());
    env.put("BUCK_INVOKING_RULE", (invokingRule.isPresent() ? invokingRule.get().toString() : ""));
    env.put("BUCK_TARGET", target.toString());
    env.put("BUCK_DIRECTORY_ROOT", context.getProjectDirectoryRoot().toString());
    env.put("BUCK_OUTPUT_ABI_FILE", pathToOutputAbiFile.or(new File("").toPath()).toString());

    processBuilder.directory(context.getProjectDirectoryRoot());
    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutor.Result result = context.getProcessExecutor().execute(processBuilder.start());
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    if (exitCode != 0) {
      return exitCode;
    }

    // Read ABI key
    if (abiKeyFile != null) {
      try {
        String firstLine = Files.readFirstLine(abiKeyFile, Charsets.UTF_8);
        if (firstLine != null) {
          // TODO(user) make sure command is considered in hash
          abiKey = new Sha1HashCode(firstLine);
        }
      } catch (IOException e) {
        e.printStackTrace(context.getStdErr());
        return 1;
      }
    }
    return 0;
  }

  private ImmutableList<Path> getExpandedSourcePaths(ExecutionContext context)
      throws IOException {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (SourcePath sourcePath : javaSourceFilePaths) {
      Path path = sourcePath.resolve();

      if (path.toString().endsWith(".java")) {
        sources.add(path);
      } else if (path.toString().endsWith(SRC_ZIP)) {
        if (!workingDirectory.isPresent()) {
          throw new HumanReadableException(
              "Attempting to compile target %s which specified a .src.zip input %s but no " +
                  "working directory was specified.",
              target.toString(),
              path);
        }
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths = Unzip.extractZipFile(
            projectFilesystem.resolve(path).toString(),
            projectFilesystem.resolve(workingDirectory.get()).toString(),
            /* overwriteExistingFiles */ true);
        sources.addAll(
            FluentIterable.from(zipPaths)
                .filter(
                    new Predicate<Path>() {
                      @Override
                      public boolean apply(Path input) {
                        return input.toString().endsWith(".java");
                      }
                    }));
      }
    }
    return sources.build();
  }

}
