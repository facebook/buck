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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;

/** This class implements starting the build prehook script. */
class BuildPrehook implements AutoCloseable {
  private static final Logger LOG = Logger.get(BuildPrehook.class);

  private ListeningProcessExecutor processExecutor;
  private BuckEventBus eventBus;
  private Cell cell;
  private BuckConfig buckConfig;
  private ImmutableMap<String, String> environment;
  private final Iterable<String> arguments;
  @Nullable ListeningProcessExecutor.LaunchedProcess process;
  @Nullable private Path tempFile;
  @Nullable private Path argumentsFile;

  BuildPrehook(
      ListeningProcessExecutor processExecutor,
      Cell cell,
      BuckEventBus eventBus,
      BuckConfig buckConfig,
      ImmutableMap<String, String> environment,
      Iterable<String> arguments) {
    this.processExecutor = processExecutor;
    this.cell = cell;
    this.eventBus = eventBus;
    this.buckConfig = buckConfig;
    this.environment = environment;
    this.arguments = arguments;
  }

  /** Start the build prehook script. */
  synchronized void startPrehookScript() throws IOException {
    Optional<String> pathToPrehookScript = buckConfig.getPathToBuildPrehookScript();
    if (!pathToPrehookScript.isPresent()) {
      return;
    }

    String pathToScript = pathToPrehookScript.get();
    if (!Paths.get(pathToScript).isAbsolute()) {
      pathToScript =
          cell.getFilesystem().getPathForRelativePath(pathToScript).toAbsolutePath().toString();
    }

    ImmutableMap.Builder<String, String> environmentBuilder =
        ImmutableMap.<String, String>builder().putAll(environment);
    tempFile = writeJsonBuckconfigFile();
    argumentsFile = createArgumentsJsonFile(arguments);
    environmentBuilder.put("BUCKCONFIG_FILE", tempFile.toString());
    environmentBuilder.put("BUCK_ROOT", cell.getRoot().toString());
    environmentBuilder.put(
        "BUCK_OUT",
        cell.getRoot()
            .resolve(cell.getFilesystem().getBuckPaths().getConfiguredBuckOut())
            .toString());
    environmentBuilder.put("BUCK_BUILD_ARGUMENTS_FILE", argumentsFile.toString());

    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addCommand(pathToScript)
            .setEnvironment(environmentBuilder.build())
            .setDirectory(cell.getFilesystem().getRootPath())
            .build();
    ByteArrayOutputStream prehookStderr = new ByteArrayOutputStream();
    ListeningProcessExecutor.ProcessListener processListener = createProcessListener(prehookStderr);
    LOG.debug("Starting build pre-hook script %s", pathToScript);
    process = processExecutor.launchProcess(processExecutorParams, processListener);
  }

  private Path createArgumentsJsonFile(Iterable<String> arguments) throws IOException {
    Path argumentsFile = cell.getFilesystem().createTempFile("arguments_", ".json");
    Files.write(
        argumentsFile,
        ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsBytes(arguments));
    return argumentsFile;
  }

  private ListeningProcessExecutor.ProcessListener createProcessListener(
      ByteArrayOutputStream prehookStderr) {
    return new ListeningProcessExecutor.ProcessListener() {
      @Override
      public void onStart(ListeningProcessExecutor.LaunchedProcess process) {
        LOG.debug("Started build pre-hook script");
      }

      @Override
      public void onStdout(ByteBuffer buffer, boolean closed) {}

      @Override
      public void onStderr(ByteBuffer buffer, boolean closed) {
        if (buffer.hasArray()) {
          prehookStderr.write(buffer.array(), buffer.position(), buffer.remaining());
          buffer.position(buffer.limit());
        } else {
          byte[] bufferBytes = new byte[buffer.remaining()];
          buffer.get(bufferBytes);
          prehookStderr.write(bufferBytes, 0, bufferBytes.length);
        }
      }

      @Override
      public boolean onStdinReady(ByteBuffer buffer) {
        return false;
      }

      @Override
      public void onExit(int exitCode) {
        LOG.debug("Finished build pre-hook script with error %s", exitCode);
        String stderrOutput = new String(prehookStderr.toByteArray(), StandardCharsets.UTF_8);
        LOG.debug("Build pre-hook script output:\n%s", stderrOutput);
        if (!stderrOutput.isEmpty()) {
          eventBus.post(ConsoleEvent.warning(stderrOutput));
        }
        // TODO(t23755518): Interrupt build when the script returns an exit code != 0.
      }
    };
  }

  /** Write a JSON file containing the buck config information. */
  private Path writeJsonBuckconfigFile() throws IOException {
    ImmutableMap<String, ImmutableMap<String, String>> values =
        buckConfig.getConfig().getRawConfig().getValues();
    Path tempFile = cell.getFilesystem().createTempFile("buckconfig_", ".json");
    Files.write(
        tempFile, ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsBytes(values));
    return tempFile;
  }

  /** Kill the build prehook script. */
  @Override
  public synchronized void close() throws IOException {
    if (process == null) {
      return;
    }
    processExecutor.destroyProcess(process, /* force */ true);
    // Removes the temporary file.
    if (tempFile != null) {
      cell.getFilesystem().deleteFileAtPath(tempFile);
    }
    if (argumentsFile != null) {
      cell.getFilesystem().deleteFileAtPath(argumentsFile);
    }
  }
}
