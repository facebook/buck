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

package com.facebook.buck.worker;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

public class WorkerProcess implements Closeable {

  private static final Logger LOG = Logger.get(WorkerProcess.class);

  private final ProcessExecutor executor;
  private final ProcessExecutorParams processParams;
  private final ProjectFilesystem filesystem;
  private final Path tmpPath;
  private final Path stdErr;
  private final AtomicInteger currentMessageID = new AtomicInteger();
  private boolean handshakePerformed = false;
  @Nullable private WorkerProcessProtocol.CommandSender protocol;
  @Nullable private ProcessExecutor.LaunchedProcess launchedProcess;

  /**
   * Worker process is a process that stays alive and receives commands which describe jobs. Worker
   * processes may be combined into pools so they can perform different jobs concurrently. It
   * communicates via JSON stream and via files. Submitted job blocks the calling thread until it
   * receives the result back. Worker process must understand the protocol that Buck will use to
   * communicate with it.
   *
   * @param executor Process executor that will start worker process.
   * @param processParams Arguments for process executor.
   * @param filesystem File system for the worker process.
   * @param tmpPath Temp folder.
   * @throws IOException In case if some I/O failure happens.
   */
  public WorkerProcess(
      ProcessExecutor executor,
      ProcessExecutorParams processParams,
      ProjectFilesystem filesystem,
      Path tmpPath)
      throws IOException {
    this.executor = executor;
    this.stdErr = Files.createTempFile("buck-worker-", "-stderr.log");
    this.processParams =
        processParams.withRedirectError(ProcessBuilder.Redirect.to(stdErr.toFile()));
    this.filesystem = filesystem;
    this.tmpPath = tmpPath;
  }

  public boolean isAlive() {
    return launchedProcess != null && launchedProcess.isAlive();
  }

  public synchronized void ensureLaunchAndHandshake() throws IOException {
    if (handshakePerformed) {
      return;
    }
    LOG.debug(
        "Starting up process %d using command: \'%s\'",
        this.hashCode(), Joiner.on(' ').join(processParams.getCommand()));
    launchedProcess = executor.launchProcess(processParams);
    protocol =
        new WorkerProcessProtocolZero.CommandSender(
            launchedProcess.getOutputStream(),
            launchedProcess.getInputStream(),
            stdErr,
            () -> {
              if (launchedProcess != null) {
                executor.destroyLaunchedProcess(launchedProcess);
              }
            });

    LOG.debug("Handshaking with process %d", this.hashCode());
    protocol.handshake(currentMessageID.getAndIncrement());
    handshakePerformed = true;
  }

  public synchronized WorkerJobResult submitAndWaitForJob(String jobArgs) throws IOException {
    Preconditions.checkState(
        protocol != null,
        "Tried to submit a job to the worker process before the handshake was performed.");

    int messageID = currentMessageID.getAndAdd(1);
    Path argsPath = Paths.get(tmpPath.toString(), String.format("%d.args", messageID));
    Path stdoutPath = Paths.get(tmpPath.toString(), String.format("%d.out", messageID));
    Path stderrPath = Paths.get(tmpPath.toString(), String.format("%d.err", messageID));
    filesystem.deleteFileAtPathIfExists(stdoutPath);
    filesystem.deleteFileAtPathIfExists(stderrPath);
    filesystem.writeContentsToPath(jobArgs, argsPath);

    LOG.debug(
        "Sending job %d to process %d \n" + " job arguments: \'%s\'",
        messageID, this.hashCode(), jobArgs);
    protocol.send(messageID, WorkerProcessCommand.of(argsPath, stdoutPath, stderrPath));
    LOG.debug("Receiving response for job %d from process %d", messageID, this.hashCode());
    int exitCode = protocol.receiveCommandResponse(messageID);
    Optional<String> stdout = filesystem.readFileIfItExists(stdoutPath);
    Optional<String> stderr = filesystem.readFileIfItExists(stderrPath);
    LOG.debug(
        "Job %d for process %d finished \n"
            + "  exit code: %d \n"
            + "  stdout: %s \n"
            + "  stderr: %s",
        messageID, this.hashCode(), exitCode, stdout.orElse(""), stderr.orElse(""));

    return WorkerJobResult.of(exitCode, stdout, stderr);
  }

  @Override
  public void close() {
    LOG.debug("Closing process %d", this.hashCode());
    try {
      if (protocol != null) {
        protocol.close();
      }
      Files.deleteIfExists(stdErr);
    } catch (Exception e) {
      LOG.debug(e, "Error closing worker process %s.", processParams.getCommand());

      LOG.debug("Worker process stderr at %s", this.stdErr.toString());

      try {
        String workerStderr =
            MoreStrings.truncatePretty(filesystem.readFileIfItExists(this.stdErr).orElse(""))
                .trim()
                .replace("\n", "\nstderr: ");
        LOG.error(
            "Worker process "
                + Joiner.on(' ').join(processParams.getCommand())
                + " failed. stderr: %s",
            workerStderr);
      } catch (Throwable t) {
        LOG.error(t, "Couldn't read stderr on failing close!");
      }

      throw new HumanReadableException(
          "Error while trying to close the worker process %s.",
          Joiner.on(' ').join(processParams.getCommand()));
    }
  }

  @VisibleForTesting
  void setProtocol(WorkerProcessProtocol.CommandSender protocolMock) {
    this.protocol = protocolMock;
  }
}
