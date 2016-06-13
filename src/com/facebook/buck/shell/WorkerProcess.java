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

package com.facebook.buck.shell;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

public class WorkerProcess {

  private static final Logger LOG = Logger.get(WorkerProcess.class);

  private final ProcessExecutor executor;
  private final ProcessExecutorParams processParams;
  private final ProjectFilesystem filesystem;
  private final Path tmpPath;
  private final AtomicInteger currentMessageID = new AtomicInteger();
  private boolean handshakePerformed = false;
  @Nullable
  private WorkerProcessProtocol protocol;
  @Nullable
  private ProcessExecutor.LaunchedProcess launchedProcess;

  public WorkerProcess(
      ProcessExecutor executor,
      ProcessExecutorParams processParams,
      ProjectFilesystem filesystem,
      Path tmpPath) throws IOException {
    this.executor = executor;
    this.processParams = processParams;
    this.filesystem = filesystem;
    this.tmpPath = tmpPath;
  }

  public synchronized void ensureLaunchAndHandshake() throws IOException {
    if (handshakePerformed) {
      return;
    }
    LOG.debug("Starting up process %d using command: \'%s\'",
        this.hashCode(),
        Joiner.on(' ').join(processParams.getCommand()));
    launchedProcess = executor.launchProcess(processParams);
    JsonWriter processStdinWriter = new JsonWriter(
        new BufferedWriter(new OutputStreamWriter(launchedProcess.getOutputStream())));
    JsonReader processStdoutReader = new JsonReader(
        new BufferedReader(new InputStreamReader(launchedProcess.getInputStream())));
    protocol = new WorkerProcessProtocolZero(
        executor,
        launchedProcess,
        processStdinWriter,
        processStdoutReader);

    int messageID = currentMessageID.getAndAdd(1);
    LOG.debug("Sending handshake to process %d", this.hashCode());
    protocol.sendHandshake(messageID);
    LOG.debug("Receiving handshake from process %d", this.hashCode());
    protocol.receiveHandshake(messageID);
    handshakePerformed = true;
  }

  public synchronized WorkerJobResult submitAndWaitForJob(String jobArgs) throws IOException {
    assert protocol != null :
        "Tried to submit a job to the worker process before the handshake was performed.";

    int messageID = currentMessageID.getAndAdd(1);
    Path argsPath = Paths.get(
        tmpPath.toString(),
        String.format("%d.args", messageID));
    Path stdoutPath = Paths.get(
        tmpPath.toString(),
        String.format("%d.out", messageID));
    Path stderrPath = Paths.get(
        tmpPath.toString(),
        String.format("%d.err", messageID));
    filesystem.writeContentsToPath(jobArgs, argsPath);

    LOG.debug("Sending job %d to process %d \n" +
        " job arguments: \'%s\'",
        messageID,
        this.hashCode(),
        jobArgs);
    protocol.sendCommand(messageID, argsPath, stdoutPath, stderrPath);
    LOG.debug("Receiving response for job %d from process %d",
        messageID,
        this.hashCode());
    int exitCode = protocol.receiveCommandResponse(messageID);
    Optional<String> stdout = filesystem.readFileIfItExists(stdoutPath);
    Optional<String> stderr = filesystem.readFileIfItExists(stderrPath);
    LOG.debug("Job %d for process %d finished \n" +
        "  exit code: %d \n" +
        "  stdout: %s \n" +
        "  stderr: %s",
        messageID,
        this.hashCode(),
        exitCode,
        stdout.or(""),
        stderr.or(""));

    return WorkerJobResult.of(exitCode, stdout, stderr);
  }

  public void close() {
    assert protocol != null :
        "Tried to close the worker process before the handshake was performed.";
    LOG.debug("Closing process %d", this.hashCode());
    try {
      protocol.close();
    } catch (IOException e) {
      LOG.debug(e, "Error closing worker process %s.", this.hashCode());
      throw new HumanReadableException(e,
          "Error while trying to close the process %s at the end of the build.",
          Joiner.on(' ').join(processParams.getCommand()));
    }
  }

  @VisibleForTesting
  void setProtocol(WorkerProcessProtocol protocolMock) {
    this.protocol = protocolMock;
  }
}
