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

package com.facebook.buck.util.network;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * This logger uses files-related operations (for offline logging i.e. storing and delayed logging
 * once network connection is better). Some of them may not be supported by all filesystems. E.g.
 * Jimfs uses JimfsPath which does not support toFile() operation. For such filesystems, the offline
 * logging will not be performed.
 */
public class OfflineScribeLogger extends ScribeLogger {

  protected static final String LOGFILE_PATTERN = "scribe-stored-*.log";
  protected static final String LOGFILE_PREFIX = "scribe-stored-";
  protected static final String LOGFILE_SUFFIX = ".log";

  private static final int KILO = 1024;
  private static final Logger LOG = Logger.get(OfflineScribeLogger.class);

  private final ScribeLogger scribeLogger;
  private final ImmutableList<String> blacklistCategories;
  private final int maxScribeOfflineLogsBytes;
  private final ProjectFilesystem filesystem;
  private final ObjectMapper objectMapper;

  private BufferedOutputStream logFileStream;
  private boolean storingAvailable;
  private int bytesStoredSoFar;
  private final Path logDir;
  private final Path newLogPath;

  public OfflineScribeLogger(
      ScribeLogger scribeLogger,
      ImmutableList<String> blacklistCategories,
      int maxScribeOfflineLogsKB,
      ProjectFilesystem projectFilesystem,
      ObjectMapper objectMapper,
      BuildId buildId) {
    Preconditions.checkNotNull(scribeLogger);
    Preconditions.checkNotNull(blacklistCategories);
    Preconditions.checkArgument(maxScribeOfflineLogsKB > 0);
    Preconditions.checkNotNull(projectFilesystem);
    Preconditions.checkNotNull(objectMapper);
    Preconditions.checkNotNull(buildId);

    this.scribeLogger = scribeLogger;
    this.blacklistCategories = blacklistCategories;
    this.maxScribeOfflineLogsBytes = KILO * maxScribeOfflineLogsKB;
    this.filesystem = projectFilesystem;
    this.objectMapper = objectMapper;

    this.logFileStream = null;
    this.storingAvailable = true;
    this.bytesStoredSoFar = 0;
    this.logDir = projectFilesystem.getBuckPaths().getOfflineLogDir();
    this.newLogPath =
        projectFilesystem.resolve(logDir.resolve(LOGFILE_PREFIX + buildId + LOGFILE_SUFFIX));
  }

  @Override
  public ListenableFuture<Void> log(final String category, final Iterable<String> lines) {
    ListenableFuture<Void> upload = scribeLogger.log(category, lines);
    Futures.addCallback(
        upload,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
          }

          @Override
          public void onFailure(Throwable t) {
            if (!blacklistCategories.contains(category)) {
              LOG.verbose("Storing Scribe lines from category: %s.", category);

              // Get data to store.
              byte[] scribeData;
              try {
                scribeData = objectMapper
                    .writeValueAsString(
                        ScribeData.builder()
                            .setCategory(category)
                            .setLines(lines)
                            .build())
                    .getBytes(Charsets.UTF_8);
              } catch (Exception e) {
                LOG.error("Failed generating JSON to store for category: %s.", category);
                return;
              }

              storeOffline(category, scribeData);
            }
          }
        });

    return upload;
  }

  @Override
  public void close() throws Exception {
    synchronized (this) {
      try {
        if (filesystem.isDirectory(logDir)) {
          deleteOldLogsIfNeeded(maxScribeOfflineLogsBytes - bytesStoredSoFar);
        }
      } catch (Exception e) {
        LOG.error("Failed to cleanup logs.\nError: %s\n%s", e.getClass(), e.getMessage());
      }

      // Store what is left in buffer and close.
      if (logFileStream != null) {
        logFileStream.close();
      }

      // Prevent any further storing to the logfile.
      storingAvailable = false;
    }
    scribeLogger.close();
  }

  private synchronized void storeOffline(String category, byte[] scribeData) {
    // Do not try to store if issues already encountered.
    if (!storingAvailable) {
      return;
    }

    // Check if this data can be logged at all.
    long spaceLeft = maxScribeOfflineLogsBytes - bytesStoredSoFar;
    if (scribeData.length > spaceLeft) {
      LOG.warn("Not enough space left when trying to store data for category: %s.", category);
      return;
    }

    try {
      // Prepare directory and the stream.
      if (logFileStream == null) {
        // Make sure directory exists.
        filesystem.mkdirs(logDir);
        // Initial dir. cleanup.
        deleteOldLogsIfNeeded(spaceLeft - scribeData.length);
        logFileStream = new BufferedOutputStream(new FileOutputStream(newLogPath.toFile()));
      }

      // Write.
      logFileStream.write(scribeData);
      bytesStoredSoFar += scribeData.length;
    } catch (Exception e) {
      storingAvailable = false;
      LOG.error(
          "Failed storing for offline logging for category: %s.\nError: %s\n%s",
          category,
          e.getClass(),
          e.getMessage());
    }
  }

  private synchronized void deleteOldLogsIfNeeded(long maxSizeForOldLogs) throws IOException {
    ImmutableSortedSet<Path> logs =
        filesystem.getSortedMatchingDirectoryContents(logDir, LOGFILE_PATTERN);

    long totalSize = 0;
    boolean deleteLogs = false;
    for (Path log : logs) {
      if (deleteLogs) {
        filesystem.deleteFileAtPathIfExists(log);
        continue;
      }

      long logSize = filesystem.getFileSize(log);
      if (totalSize + logSize > maxSizeForOldLogs) {
        deleteLogs = true;
        filesystem.deleteFileAtPathIfExists(log);
      } else {
        totalSize += logSize;
      }
    }
  }
}
