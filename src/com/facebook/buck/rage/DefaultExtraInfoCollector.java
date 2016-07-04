/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rage;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs an optional user-specified command to get extra info for the rage report.
 */
public class DefaultExtraInfoCollector implements ExtraInfoCollector {

  private static final long PROCESS_TIMEOUT_MS = 5 * 60 * 1000; // 5min.

  private final RageConfig rageConfig;
  private final ProjectFilesystem projectFilesystem;
  private final ProcessExecutor processExecutor;

  public DefaultExtraInfoCollector(
      RageConfig rageConfig,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor) {
    this.rageConfig = rageConfig;
    this.projectFilesystem = projectFilesystem;
    this.processExecutor = processExecutor;
  }

  @Override
  public Optional<ExtraInfoResult> run()
      throws IOException, InterruptedException, ExtraInfoExecutionException {
    ImmutableList<String> extraInfoCommand = rageConfig.getExtraInfoCommand();
    if (extraInfoCommand.isEmpty()) {
      return Optional.absent();
    }

    // TODO(ruibm): Potentially add the initial static launch dir here as well as any launch-*
    // logs buck is currently generating.
    Path rageExtraFilesDir =
        projectFilesystem.getBuckPaths().getLogDir().resolve("rage-extra-info");
    projectFilesystem.deleteRecursivelyIfExists(rageExtraFilesDir);
    projectFilesystem.mkdirs(rageExtraFilesDir);

    ProcessExecutor.Result extraInfoResult;
    try {
      extraInfoResult = processExecutor.launchAndExecute(
          ProcessExecutorParams.builder()
              .addAllCommand(extraInfoCommand)
              .addCommand(
                  "--output-dir",
                  projectFilesystem.resolve(rageExtraFilesDir).toString())
              .setDirectory(projectFilesystem.getRootPath().toFile())
              .build(),
          ImmutableSet.of(
              ProcessExecutor.Option.EXPECTING_STD_OUT,
              ProcessExecutor.Option.PRINT_STD_ERR),
          Optional.<String>absent(),
          Optional.of(PROCESS_TIMEOUT_MS),
          Optional.<Function<Process, Void>>absent()
      );
    } catch (IOException e) {
      throw new ExtraInfoExecutionException("Could not invoke extra report command.", e);
    }

    if (extraInfoResult.isTimedOut()) {
      throw new ExtraInfoExecutionException(
          String.format(
              "Gathering extra information for rage report from %s timed out after %d ms",
              extraInfoCommand,
              PROCESS_TIMEOUT_MS));
    }

    if (extraInfoResult.getExitCode() != 0) {
      throw new ExtraInfoExecutionException(
          String.format(
              "Could not get extra info for report from %s, exit code %s",
              extraInfoCommand,
              extraInfoResult.getExitCode()));
    }

    ImmutableSet<Path> rageExtraFiles = projectFilesystem.getFilesUnderPath(rageExtraFilesDir);

    return Optional.of(
        ExtraInfoResult.builder()
            .setExtraFiles(rageExtraFiles)
            .setOutput(extraInfoResult.getStdout().or(""))
            .build());

  }

}
