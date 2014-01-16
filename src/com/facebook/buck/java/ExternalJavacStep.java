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
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class ExternalJavacStep extends JavacStep {

  private final Path javac;
  private final BuildTarget target;

  public ExternalJavacStep(
      Path outputDirectory,
      Set<Path> javaSourceFilePaths,
      Set<String> transitiveClasspathEntries,
      Set<String> declaredClasspathEntries,
      JavacOptions javacOptions,
      Optional<Path> pathToOutputAbiFile,
      Optional<String> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules,
      Optional<Path> pathToSrcsList,
      Path javac,
      BuildTarget target) {
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
    this.javac = Preconditions.checkNotNull(javac);
    this.target = Preconditions.checkNotNull(target);
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder builder = new StringBuilder(javac.toString());
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
    return javac.toString();
  }

  @Override
  protected int buildWithClasspath(ExecutionContext context, Set<String> buildClasspathEntries) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.add(javac.toString());
    command.addAll(getOptions(context, buildClasspathEntries));

    // Add sources file or sources list to command
    if (pathToSrcsList.isPresent()) {
      try {
        context.getProjectFilesystem().writeLinesToPath(
            Iterables.transform(javaSourceFilePaths, Functions.toStringFunction()),
            pathToSrcsList.get());
        command.add("@" + pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(e,
            "Cannot write list of .java files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    } else {
      for (Path source : getSrcs()) {
        command.add(source.toString());
      }
    }

    ProcessBuilder pb = new ProcessBuilder(command.build());

    // Add additional information to the environment
    Map<String, String> env = pb.environment();
    env.put("BUCK_INVOKING_RULE", invokingRule.or(""));
    env.put("BUCK_TARGET", target.toString());
    env.put("BUCK_DIRECTORY_ROOT", context.getProjectDirectoryRoot().toString());
    env.put("BUCK_OUTPUT_ABI_FILE", pathToOutputAbiFile.or(new File("").toPath()).toString());

    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutor.Result result = context.getProcessExecutor().execute(pb.start());
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

}
