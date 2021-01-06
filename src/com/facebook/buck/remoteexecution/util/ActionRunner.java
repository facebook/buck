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

package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.util.OutputsCollector.CollectedOutputs;
import com.facebook.buck.remoteexecution.util.OutputsCollector.FilesystemBackedDelegate;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams.Builder;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Runs an action (command + environment) in a directory and returns the results (exit code,
 * stdout/stderr, and outputs).
 */
public class ActionRunner {
  private final Protocol protocol;
  private final BuckEventBus eventBus;

  public ActionRunner(Protocol protocol, BuckEventBus eventBus) {
    this.protocol = protocol;
    this.eventBus = eventBus;
  }

  /** Results of an action. */
  public static class ActionResult {
    public final ImmutableList<OutputFile> outputFiles;
    public final ImmutableList<OutputDirectory> outputDirectories;
    public final ImmutableSet<UploadDataSupplier> requiredData;
    public final int exitCode;
    public final String stderr;
    public final String stdout;

    ActionResult(
        ImmutableList<OutputFile> outputFiles,
        ImmutableList<OutputDirectory> outputDirectories,
        ImmutableSet<UploadDataSupplier> requiredData,
        int exitCode,
        String stderr,
        String stdout) {
      this.outputFiles = outputFiles;
      this.outputDirectories = outputDirectories;
      this.requiredData = requiredData;
      this.exitCode = exitCode;
      this.stderr = stderr;
      this.stdout = stdout;
    }
  }

  /** Runs an action and returns the result. */
  public ActionResult runAction(
      ImmutableList<String> command,
      ImmutableMap<String, String> environmentOverrides,
      Set<Path> outputs,
      Path buildDir)
      throws IOException, InterruptedException {
    Console console;
    Builder paramsBuilder;
    try (Scope ignored = LeafEvents.scope(eventBus, "preparing_action")) {
      paramsBuilder = ProcessExecutorParams.builder();
      paramsBuilder.setCommand(command);

      ImmutableMap.Builder<String, String> environment =
          ImmutableMap.builderWithExpectedSize(environmentOverrides.size() + 1);
      environment.putAll(environmentOverrides);
      if (!environmentOverrides.containsKey("PATH")) {
        // Propagate `PATH` so we can find the expected version of `java` in tests.
        ImmutableMap<String, String> currentProcessEnvironment =
            EnvVariablesProvider.getSystemEnv();
        environment.put("PATH", currentProcessEnvironment.get("PATH"));
      }
      paramsBuilder.setEnvironment(environment.build());

      paramsBuilder.setDirectory(buildDir);
      CapturingPrintStream stdOut = new CapturingPrintStream();
      CapturingPrintStream stdErr = new CapturingPrintStream();
      console = new Console(Verbosity.STANDARD_INFORMATION, stdOut, stdErr, Ansi.withoutTty());
    }

    Result result;
    try (Scope ignored = LeafEvents.scope(eventBus, "subprocess")) {
      result = new DefaultProcessExecutor(console).launchAndExecute(paramsBuilder.build());
    }

    CollectedOutputs collectedOutputs;
    try (Scope ignored = LeafEvents.scope(eventBus, "collecting_outputs")) {
      if (result.getExitCode() == 0) {
        // TODO(cjhopman): Should outputs be returned on failure?
        collectedOutputs =
            new OutputsCollector(protocol, new FilesystemBackedDelegate())
                .collect(outputs, buildDir);
      } else {
        collectedOutputs =
            new CollectedOutputs(ImmutableList.of(), ImmutableList.of(), ImmutableSet.of());
      }
    }

    return new ActionResult(
        collectedOutputs.outputFiles,
        collectedOutputs.outputDirectories,
        collectedOutputs.requiredData,
        result.getExitCode(),
        result.getStderr().get(),
        result.getStdout().get());
  }
}
