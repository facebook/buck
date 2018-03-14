/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.modern.builders.thrift.ActionResult;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.env.BuckClasspath;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;

/**
 * An implementation of IsolatedExecution that uses a ContentAddressedStorage and
 * RemoteExecutionService to execute build rules.
 *
 * <p>See https://docs.google.com/document/d/1AaGk7fOPByEvpAbqeXIyE8HX_A3_axxNnvroblTZ_6s/preview
 * for a high-level description of the approach to remote execution.
 */
class RemoteExecution implements IsolatedExecution {
  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  private final ContentAddressedStorage storage;
  private final RemoteExecutionService executionService;
  private final byte[] trampoline;

  RemoteExecution(ContentAddressedStorage storage, RemoteExecutionService executionService)
      throws IOException {
    this.storage = storage;
    this.executionService = executionService;
    this.trampoline = Files.readAllBytes(TRAMPOLINE);
  }

  @Override
  public void build(
      ExecutionContext executionContext,
      InputsDigestBuilder inputsBuilder,
      Set<Path> outputs,
      Path projectRoot,
      HashCode hash,
      BuildTarget buildTarget,
      Path cellPrefixRoot)
      throws IOException, InterruptedException, StepFailedException {
    ImmutableList<Path> classpath = BuckClasspath.getClasspath();
    ImmutableList<Path> bootstrapClasspath = BuckClasspath.getBootstrapClasspath();

    ImmutableList<Path> isolatedClasspath =
        processClasspath(inputsBuilder, cellPrefixRoot, classpath);
    ImmutableList<Path> isolatedBootstrapClasspath =
        processClasspath(inputsBuilder, cellPrefixRoot, bootstrapClasspath);

    Path trampolinePath = Paths.get("./__trampoline__.sh");
    ImmutableList<String> command = getBuilderCommand(trampolinePath, projectRoot, hash.toString());
    ImmutableSortedMap<String, String> commandEnvironment =
        getBuilderEnvironmentOverrides(isolatedBootstrapClasspath, isolatedClasspath);

    inputsBuilder.addFile(trampolinePath, () -> trampoline, true);

    Inputs inputs = inputsBuilder.build();
    storage.addMissing(inputs.getRequiredData());

    ActionResult result =
        executionService.execute(command, commandEnvironment, inputs.getRootDigest(), outputs);

    if (result.exitCode != 0) {
      throw StepFailedException.createForFailingStepWithExitCode(
          new AbstractExecutionStep("remote_execution") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              throw new RuntimeException();
            }
          },
          executionContext,
          StepExecutionResult.of(result.exitCode, getStdErr(result)),
          Optional.of(buildTarget));
    }

    for (Path path : outputs) {
      MostFiles.deleteRecursivelyIfExists(cellPrefixRoot.resolve(path));
    }
    storage.materializeOutputs(result.outputDirectories, result.outputFiles, cellPrefixRoot);
  }

  private Optional<String> getStdErr(ActionResult result) {
    if (result.isSetStderrRaw()) {
      return Optional.of(
          new String(
              result.stderrRaw.array(),
              result.stderrRaw.arrayOffset(),
              result.stderrRaw.limit(),
              Charsets.UTF_8));
    }
    // TODO(cjhopman): Implement this.
    return Optional.empty();
  }

  private ImmutableSortedMap<String, String> getBuilderEnvironmentOverrides(
      ImmutableList<Path> bootstrapClasspath, Iterable<Path> classpath) {
    return ImmutableSortedMap.of(
        "CLASSPATH", classpathArg(bootstrapClasspath), "BUCK_CLASSPATH", classpathArg(classpath));
  }

  private ImmutableList<String> getBuilderCommand(
      Path trampolinePath, Path projectRoot, String hash) {
    String rootString = projectRoot.toString();
    if (rootString.isEmpty()) {
      rootString = "./";
    }
    return ImmutableList.of(trampolinePath.toString(), rootString, hash);
  }

  private String classpathArg(Iterable<Path> classpath) {
    return Joiner.on(File.pathSeparator).join(classpath);
  }

  private ImmutableList<Path> processClasspath(
      InputsDigestBuilder inputsBuilder, Path cellPrefix, Iterable<Path> classPath) {
    ImmutableList.Builder<Path> resolvedBuilder = ImmutableList.builder();
    for (Path path : classPath) {
      Preconditions.checkState(path.isAbsolute());
      if (!path.startsWith(cellPrefix)) {
        resolvedBuilder.add(path);
      } else {
        Path relative = cellPrefix.relativize(path);
        inputsBuilder.addFile(relative, false);
        resolvedBuilder.add(relative);
      }
    }
    return resolvedBuilder.build();
  }

  @Override
  public void close() throws IOException {}
}
