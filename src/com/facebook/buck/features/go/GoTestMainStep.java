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

package com.facebook.buck.features.go;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class GoTestMainStep extends ShellStep {
  private final ImmutableMap<String, String> environment;
  private final ImmutableList<String> generatorCommandPrefix;
  private final GoTestCoverStep.Mode coverageMode;
  private final ImmutableMap<Path, ImmutableMap<String, Path>> coverageVariables;
  private final Path packageName;
  private final Iterable<Path> testFiles;
  private final Path output;

  public GoTestMainStep(
      Path workingDirectory,
      ImmutableMap<String, String> environment,
      ImmutableList<String> generatorCommandPrefix,
      GoTestCoverStep.Mode coverageMode,
      ImmutableMap<Path, ImmutableMap<String, Path>> coverageVariables,
      Path packageName,
      Iterable<Path> testFiles,
      Path output) {
    super(workingDirectory);
    this.environment = environment;
    this.generatorCommandPrefix = generatorCommandPrefix;
    this.coverageMode = coverageMode;
    this.coverageVariables = coverageVariables;
    this.packageName = packageName;
    this.testFiles = testFiles;
    this.output = output;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> command =
        ImmutableList.<String>builder()
            .addAll(generatorCommandPrefix)
            .add("--output", output.toString())
            .add("--import-path", packageName.toString())
            .add("--cover-mode", coverageMode.getMode());

    Set<Path> filteredFileNames =
        Streams.stream(testFiles).map(Path::getFileName).collect(Collectors.toSet());
    for (Map.Entry<Path, ImmutableMap<String, Path>> pkg : coverageVariables.entrySet()) {
      if (pkg.getValue().isEmpty()) {
        continue;
      }

      StringBuilder pkgFlag = new StringBuilder();
      pkgFlag.append(pkg.getKey());
      pkgFlag.append(':');

      boolean first = true;
      for (Map.Entry<String, Path> pkgVars : pkg.getValue().entrySet()) {
        if (filteredFileNames.contains(pkgVars.getValue())) {
          if (!first) {
            pkgFlag.append(',');
          }
          first = false;
          pkgFlag.append(pkgVars.getKey());
          pkgFlag.append('=');
          pkgFlag.append(pkgVars.getValue());
        }
      }

      command.add("--cover-pkgs", pkgFlag.toString());
    }

    for (Path source : testFiles) {
      command.add(source.toString());
    }

    return command.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return environment;
  }

  @Override
  public String getShortName() {
    return "go test main gen";
  }
}
