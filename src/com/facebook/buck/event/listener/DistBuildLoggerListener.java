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

package com.facebook.buck.event.listener;

import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Charsets;
import com.google.common.eventbus.Subscribe;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class DistBuildLoggerListener implements BuckEventListener, Closeable {
  private static final Logger LOG = Logger.get(DistBuildLoggerListener.class);

  private static final byte[] NEWLINE = "\n".getBytes(Charsets.UTF_8);

  private final Path logDirectoryPath;
  private final ExecutorService executor;
  private final ProjectFilesystem filesystem;

  private Map<RunIdStreamPair, Integer> currentLogLine = new HashMap<>();
  private Set<String> rematerializedLogDirsByRunId = new HashSet<>();
  private Set<String> createdLogDirsByRunId = new HashSet<>();

  public DistBuildLoggerListener(
      Path logDirectoryPath,
      ProjectFilesystem filesystem,
      ExecutorService executor) {
    this.logDirectoryPath = logDirectoryPath;
    this.filesystem = filesystem;
    this.executor = executor;
  }

  @Subscribe
  public void distributedBuildStatus(DistBuildStatusEvent event) throws IOException {
    if (!event.getStatus().getSlaveInfoByRunId().isPresent()) {
      return;
    }
    Map<String, BuildSlaveInfo> slaveInfoByRunId = event.getStatus().getSlaveInfoByRunId().get();
    for (String runId : slaveInfoByRunId.keySet()) {
      BuildSlaveInfo buildSlaveInfo = slaveInfoByRunId.get(runId);

      if (buildSlaveInfo.isSetStdOut()) {
        processLogForStream(runId, "out", buildSlaveInfo.stdOut);
      }
      if (buildSlaveInfo.isSetStdErr()) {
        processLogForStream(runId, "err", buildSlaveInfo.stdErr);
      }

      if (buildSlaveInfo.isSetLogDirZipContents() &&
          !rematerializedLogDirsByRunId.contains(runId)) {
        rematerializeLogDirZip(runId, buildSlaveInfo.logDirZipContents.array());
        rematerializedLogDirsByRunId.add(runId);
      }
    }
  }

  private void rematerializeLogDirZip(String runId, byte[] zipContents) throws IOException {
    if (zipContents.length == 0) {
      LOG.warn("Skipping materialiation of log zip for runId [%s] as content length was zero");
      return;
    }
    try (FileOutputStream zipFos = new FileOutputStream(getLogDirZipPath(runId).toFile())) {
      zipFos.write(zipContents);
    }
  }

  private void processLogForStream(String runId, String streamType, List<String> fullLog) {

    RunIdStreamPair runIdStream = RunIdStreamPair
        .builder().setRunId(runId).setStream(streamType).build();
    if (!currentLogLine.containsKey(runIdStream)) {
      currentLogLine.put(runIdStream, 0);
    }
    int startIndex = currentLogLine.get(runIdStream); // Inclusive
    int endIndex = fullLog.size(); // Exclusive
    if (startIndex >= endIndex) {
      return; // No more log lines to process
    }
    List<String> logLinesToWrite = fullLog.subList(startIndex, endIndex);
    currentLogLine.put(runIdStream, endIndex);

    writeToLog(runId, streamType, logLinesToWrite);
  }

  private Path getLogDirForRunId(String runId) {
    Path runIdLogDir = filesystem.resolve(logDirectoryPath).resolve(String.format(
        BuckConstant.DIST_BUILD_SLAVE_LOG_DIR_NAME_TEMPLATE,
        runId));

    if (!createdLogDirsByRunId.contains(runId)) {
      try {
        filesystem.mkdirs(runIdLogDir);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      createdLogDirsByRunId.add(runId);
    }

    return runIdLogDir;
  }

  private Path getStreamLogFilePath(String runId, String streamType) {
    return getLogDirForRunId(runId).resolve(String.format("%s.log", streamType));
  }

  private Path getLogDirZipPath(String runId) {
    return getLogDirForRunId(runId).resolve("buck-out-logs.zip");
  }

  private void writeToLog(
      final String runId,
      String streamType,
      final List<String> logLines) {
    executor.submit(() -> {
      try (OutputStream outputStream = new BufferedOutputStream(
          new FileOutputStream(
              getStreamLogFilePath(runId, streamType).toFile(), /* append */
              true))) {
        for (String logLine : logLines) {
          outputStream.write((logLine.getBytes(Charsets.UTF_8)));
          outputStream.write(NEWLINE);
        }
        outputStream.flush();
      } catch (IOException e) {
        LOG.debug(
            "Failed to write to %s",
            getStreamLogFilePath(runId, streamType).toAbsolutePath(),
            e);
      }
    });
  }

  @Override
  public void outputTrace(BuildId buildId) {
    executor.shutdown();
  }

  @Override
  public void close() throws IOException {
    executor.shutdown();
  }
}
