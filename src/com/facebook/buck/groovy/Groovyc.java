/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Groovyc {

  private static final Function<String, String> ARGFILES_ESCAPER =
      Escaper.escaper(
          Escaper.Quoter.DOUBLE,
          CharMatcher.anyOf("#\"'").or(CharMatcher.WHITESPACE));
  private static final String SRC_ZIP = ".src.zip";
  private static final String SRC_JAR = "-sources.jar";

  private final Path pathToGroovyc;

  public Groovyc(final Path pathToGroovyc) {
    this.pathToGroovyc = pathToGroovyc;
  }

  public int buildWithClasspath(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<Path> groovySourceFilePaths,
      Optional<Path> pathToSrcsList,
      Optional<Path> workingDirectory) throws InterruptedException {

    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.add(pathToGroovyc.toString());
    command.add("-j");
    command.add("-Jtarget=1.8");
    command.add("-Jsource=1.8");
    command.addAll(options);

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = getExpandedSourcePaths(
          filesystem,
          invokingRule,
          groovySourceFilePaths,
          workingDirectory);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule,
          workingDirectory);
    }

    if (pathToSrcsList.isPresent()) {
      try {
        filesystem.writeLinesToPath(
            FluentIterable.from(expandedSources)
                .transform(Functions.toStringFunction())
                .transform(ARGFILES_ESCAPER),
            pathToSrcsList.get());
        command.add("@" + pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(
            e,
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
    env.put("BUCK_INVOKING_RULE", invokingRule.toString());
    env.put("BUCK_TARGET", invokingRule.toString());
    env.put("BUCK_DIRECTORY_ROOT", filesystem.getRootPath().toAbsolutePath().toString());

    processBuilder.directory(filesystem.getRootPath().toAbsolutePath().toFile());
    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutor.Result result = context.getProcessExecutor().execute(processBuilder.start());
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;

  }

  public String getDescription(
      ImmutableList<String> options,
      ImmutableSet<Path> groovySourceFilePaths,
      Optional<Path> pathToSrcsList) {

    StringBuilder builder = new StringBuilder(pathToGroovyc.toString());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, groovySourceFilePaths);
    }

    return builder.toString();
  }

  public String getShortName() {
    return pathToGroovyc.toString();
  }

  private ImmutableList<Path> getExpandedSourcePaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> workingDirectory) throws IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java") || pathString.endsWith(".groovy")) {
        sources.add(path);
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        if (!workingDirectory.isPresent()) {
          throw new HumanReadableException(
              "Attempting to compile target %s which specified a .src.zip input %s but no " +
                  "working directory was specified.",
              invokingRule.toString(),
              path);
        }
        // For a Zip of .java and .groovy files, create a Path for each entry.
        ImmutableList<Path> zipPaths = Unzip.extractZipFile(
            projectFilesystem.resolve(path),
            projectFilesystem.resolve(workingDirectory.get()),
            Unzip.ExistingFileMode.OVERWRITE);
        sources.addAll(
            FluentIterable.from(zipPaths)
                .filter(
                    new Predicate<Path>() {
                      @Override
                      public boolean apply(Path input) {
                        return input.toString().endsWith(".java") ||
                            input.toString().endsWith(".groovy");
                      }
                    }));
      }
    }
    return sources.build();
  }

}
