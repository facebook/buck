/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.LogDir;
import com.facebook.buck.distributed.thrift.MultiGetBuildSlaveLogDirResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.zip.Unzip;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Materializes locally logs from remote BuildSlaves. */
public class BuildSlaveLogsMaterializer {
  private static final Logger LOG = Logger.get(BuildSlaveLogsMaterializer.class);

  private final DistBuildService service;
  private final ProjectFilesystem filesystem;
  private final Path logDirectoryPath;
  private final List<BuildSlaveRunId> materializedRunIds;

  public BuildSlaveLogsMaterializer(
      DistBuildService service, ProjectFilesystem filesystem, Path logDirectoryPath) {
    this.service = service;
    this.filesystem = filesystem;
    this.logDirectoryPath = logDirectoryPath;
    this.materializedRunIds = Lists.newArrayList();
  }

  /** Fetches and materializes all logs directories. */
  public void fetchAndMaterializeLogDirs(
      StampedeId stampedeId, List<BuildSlaveRunId> toMaterialize) {
    List<LogDir> logDirs = fetchBuildSlaveLogDirs(stampedeId, toMaterialize);
    materializeLogDirs(logDirs);
  }

  /** Fetches the logs directory of a BuildSlave. */
  public List<LogDir> fetchBuildSlaveLogDirs(
      StampedeId stampedeId, List<BuildSlaveRunId> toMaterialize) {
    if (toMaterialize.size() == 0) {
      return Lists.newArrayList();
    }

    try {
      MultiGetBuildSlaveLogDirResponse logDirsResponse =
          service.fetchBuildSlaveLogDir(stampedeId, toMaterialize);
      Preconditions.checkState(logDirsResponse.isSetLogDirs());
      return logDirsResponse.getLogDirs();
    } catch (IOException ex) {
      // It's not critical if we were unable to retrieve the remote log dirs as it can always be
      // done later on with just the StampedeId.
      LOG.error(ex, "Error fetching slave log directories from frontend.");
      return Lists.newArrayList();
    }
  }

  /** Materializes locally all arguments log directories. */
  public void materializeLogDirs(List<LogDir> logDirs) {
    for (LogDir logDir : logDirs) {
      if (logDir.isSetErrorMessage()) {
        LOG.error(
            "Failed to fetch log dir for runId [%s]. Error: %s",
            logDir.buildSlaveRunId, logDir.errorMessage);
        continue;
      }

      try {
        writeLogDirToDisk(logDir);
      } catch (IOException e) {
        LOG.error(e, "Error while materializing log dir for runId [%s].", logDir.buildSlaveRunId);
      }
    }
  }

  private void writeLogDirToDisk(LogDir logDir) throws IOException {
    if (logDir.data.array().length == 0) {
      LOG.warn(
          "Skipping materialiation of remote buck-out log dir for runId [%s]"
              + " as content length was zero.",
          logDir.buildSlaveRunId);
      return;
    }

    Path buckLogUnzipPath = getRemoteBuckLogPath(logDir.buildSlaveRunId.id);

    try (NamedTemporaryFile zipFile = new NamedTemporaryFile("remoteBuckLog", "zip")) {
      Files.write(zipFile.get(), logDir.data.array());
      Unzip.extractZipFile(
          zipFile.get(),
          filesystem,
          buckLogUnzipPath,
          Unzip.ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    }

    materializedRunIds.add(logDir.buildSlaveRunId);
  }

  private Path getRemoteBuckLogPath(String runId) throws IOException {
    Path remoteBuckLogPath = DistBuildUtil.getRemoteBuckLogPath(runId, logDirectoryPath);
    createLogDir(remoteBuckLogPath.getParent());
    return remoteBuckLogPath;
  }

  private void createLogDir(Path logDir) throws IOException {
    filesystem.mkdirs(logDir);
  }

  public List<BuildSlaveRunId> getMaterializedRunIds() {
    return materializedRunIds;
  }
}
