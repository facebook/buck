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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
  @Nullable ListeningProcessExecutor.LaunchedProcess process;
  @Nullable private NamedTemporaryFile tempFile;

  public BuildPrehook(
      ListeningProcessExecutor processExecutor,
      Cell cell,
      BuckEventBus eventBus,
      BuckConfig buckConfig,
      ImmutableMap<String, String> environment)
      throws IOException {
    this.processExecutor = processExecutor;
    this.cell = cell;
    this.eventBus = eventBus;
    this.buckConfig = buckConfig;
    this.environment = environment;
  }

  /** Start the build prehook script. */
  public synchronized void startPrehookScript() throws IOException, InterruptedException {
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
    writeJsonBuckconfigFile();
    Preconditions.checkState(tempFile != null);
    environmentBuilder.put("BUCKCONFIG_FILE", tempFile.get().toString());
    environmentBuilder.put("BUCK_ROOT", cell.getRoot().toString());
    environmentBuilder.put(
        "BUCK_OUT",
        cell.getRoot()
            .resolve(cell.getFilesystem().getBuckPaths().getConfiguredBuckOut())
            .toString());

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
        try {
          String stderrOutput = new String(prehookStderr.toByteArray(), "UTF-8");
          LOG.debug("Build pre-hook script output:\n%s", stderrOutput);
          eventBus.post(ConsoleEvent.warning(stderrOutput));
        } catch (UnsupportedEncodingException e) {
          LOG.error("The build pre-hook script output unsupported encoding");
        }
        // TODO(t23755518): Interrupt build when the script returns an exit code != 0.
      }
    };
  }

  /** Write a JSON file containing the buck config information. */
  private void writeJsonBuckconfigFile() throws IOException {
    ImmutableMap<String, ImmutableMap<String, String>> values =
        buckConfig.getConfig().getRawConfig().getValues();
    StringWriter stringWriter = new StringWriter();
    ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValue(stringWriter, values);
    tempFile = new NamedTemporaryFile("buckconfig_", ".json");
    Files.write(tempFile.get(), stringWriter.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** Kill the build prehook script. */
  @Override
  public synchronized void close() throws InterruptedException, IOException {
    if (process == null) {
      return;
    }
    processExecutor.destroyProcess(process, /* force */ true);
    // Removes the temporary file.
    if (tempFile != null) {
      tempFile.close();
    }
  }
}
