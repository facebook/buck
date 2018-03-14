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
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams.Builder;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.io.MoreFiles;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** IsolatedExecution implementation that will run buildrules in a subprocess. */
public class OutOfProcessIsolatedExecution implements IsolatedExecution {
  private final NamedTemporaryDirectory workDir;
  private final LocalContentAddressedStorage storage;
  private final byte[] trampoline;

  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  OutOfProcessIsolatedExecution() throws IOException {
    this.workDir = new NamedTemporaryDirectory("__work__");
    this.storage = new LocalContentAddressedStorage(workDir.getPath().resolve("__cache__"));
    this.trampoline = Files.readAllBytes(TRAMPOLINE);
  }

  @Override
  public void close() throws IOException {
    workDir.close();
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
      throws IOException, StepFailedException, InterruptedException {
    String dirName =
        String.format(
            "%.40s-%d",
            buildTarget.getShortNameAndFlavorPostfix(),
            buildTarget.getFullyQualifiedName().hashCode());
    Path buildDir = workDir.getPath().resolve(dirName);

    try (Closeable ignored = () -> MostFiles.deleteRecursively(buildDir)) {
      ImmutableList<Path> classpath = BuckClasspath.getClasspath();
      ImmutableList<Path> bootstrapClasspath = BuckClasspath.getBootstrapClasspath();

      ImmutableList<Path> isolatedClasspath =
          processClasspath(inputsBuilder, cellPrefixRoot, classpath);
      ImmutableList<Path> isolatedBootstrapClasspath =
          processClasspath(inputsBuilder, cellPrefixRoot, bootstrapClasspath);

      Path trampolinePath = Paths.get("./__trampoline__.sh");

      inputsBuilder.addFile(trampolinePath, () -> trampoline, true);

      Inputs inputs = inputsBuilder.build();
      storage.addMissing(inputs.getRequiredData());
      storage.materializeInputs(buildDir, inputs.getRootDigest());

      runOutOfProcessBuilder(
          executionContext,
          buildDir,
          projectRoot,
          trampolinePath,
          hash.toString(),
          isolatedBootstrapClasspath,
          isolatedClasspath);

      materializeOutputs(outputs, buildDir, cellPrefixRoot);
    }
  }

  private void runOutOfProcessBuilder(
      ExecutionContext context,
      Path buildDir,
      Path projectRoot,
      Path trampolinePath,
      String hash,
      ImmutableList<Path> isolatedBootstrapClasspath,
      ImmutableList<Path> isolatedClasspath)
      throws StepFailedException, IOException, InterruptedException {
    Builder paramsBuilder = ProcessExecutorParams.builder();
    paramsBuilder.setCommand(getBuilderCommand(trampolinePath, projectRoot, hash));
    paramsBuilder.setEnvironment(
        MoreMaps.merge(
            context.getEnvironment(),
            getBuilderEnvironmentOverrides(isolatedBootstrapClasspath, isolatedClasspath)));
    paramsBuilder.setDirectory(buildDir);
    Result result = context.getProcessExecutor().launchAndExecute(paramsBuilder.build());
    if (result.getExitCode() != 0) {
      throw StepFailedException.createForFailingStepWithExitCode(
          new AbstractExecutionStep("") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              throw Assertions.assertUnreachable();
            }
          },
          context,
          StepExecutionResult.of(result.getExitCode(), result.getStderr()),
          Optional.empty());
    }
  }

  private Map<String, String> getBuilderEnvironmentOverrides(
      ImmutableList<Path> bootstrapClasspath, Iterable<Path> classpath) {
    return ImmutableMap.of(
        "CLASSPATH", classpathArg(bootstrapClasspath), "BUCK_CLASSPATH", classpathArg(classpath));
  }

  private Iterable<String> getBuilderCommand(Path trampolinePath, Path projectRoot, String hash) {
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

  private void materializeOutputs(Iterable<Path> outputs, Path buildDir, Path materializeDir) {
    for (Path path : outputs) {
      Preconditions.checkState(!path.isAbsolute());
      try {
        Path dest = materializeDir.resolve(path);
        Path source = buildDir.resolve(path);
        MoreFiles.createParentDirectories(dest);
        if (Files.isDirectory(source)) {
          MostFiles.copyRecursively(source, dest);
        } else {
          Files.copy(source, dest);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
