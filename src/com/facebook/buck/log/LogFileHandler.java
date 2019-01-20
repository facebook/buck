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

package com.facebook.buck.log;

import com.facebook.buck.util.DirectoryCleaner;
import com.facebook.buck.util.DirectoryCleanerArgs;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

public class LogFileHandler extends Handler {

  private final LogFileHandlerState state;

  public LogFileHandler() throws SecurityException {
    this(GlobalStateManager.singleton().getLogFileHandlerState(), new LogFormatter());
  }

  @VisibleForTesting
  LogFileHandler(LogFileHandlerState state, LogFormatter formatter) throws SecurityException {
    this.state = state;
    setFormatter(formatter);
  }

  public static long getMaxSizeBytes() {
    return getConfig("max_size_bytes", LogConfigSetup.DEFAULT_MAX_LOG_SIZE_BYTES);
  }

  public static int getMaxLogCount() {
    return (int) getConfig("count", LogConfigSetup.DEFAULT_MAX_COUNT);
  }

  public static int getMinAmountOfLogsToKeep() {
    return (int) getConfig("min_count", LogConfigSetup.DEFAULT_MIN_COUNT);
  }

  private static long getConfig(String suffix, long defaultValue) {
    String maxSizeBytesStr =
        LogManager.getLogManager().getProperty(LogFileHandler.class.getName() + "." + suffix);
    if (maxSizeBytesStr == null) {
      return defaultValue;
    }

    return Long.parseLong(maxSizeBytesStr);
  }

  @Override
  public void publish(LogRecord record) {
    String commandId = state.threadIdToCommandId(record.getThreadID());
    String formattedMsg = getFormatter().format(record);
    for (Writer writer : state.getWriters(commandId)) {
      try {
        writer.write(formattedMsg);
        if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
          writer.flush();
        }
      } catch (IOException e) { // NOPMD
        // There's a chance the writer may have been concurrently closed.
      }
    }
  }

  @Override
  public void flush() {
    for (Writer writer : state.getWriters(null)) {
      try {
        writer.flush();
      } catch (IOException e) { // NOPMD
        // There's a chance the writer may have been concurrently closed.
      }
    }
  }

  @Override
  public void close() throws SecurityException {
    // The streams are controlled globally by the GlobalStateManager.
  }

  public static DirectoryCleaner newCleaner() {
    return newCleaner(getMaxSizeBytes(), getMaxLogCount(), getMinAmountOfLogsToKeep());
  }

  public static DirectoryCleaner newCleaner(
      long maxLogsSizeBytes, int maxLogsDirectories, int minAmountOfLogsToKeep) {
    DirectoryCleanerArgs cleanerArgs =
        DirectoryCleanerArgs.builder()
            .setPathSelector(
                new DirectoryCleaner.PathSelector() {

                  @Override
                  public Iterable<Path> getCandidatesToDelete(Path rootPath) throws IOException {
                    List<Path> dirPaths = new ArrayList<>();
                    try (DirectoryStream<Path> directoryStream =
                        Files.newDirectoryStream(rootPath)) {
                      for (Path path : directoryStream) {
                        if (AbstractInvocationInfo.isLogDirectory(path)) {
                          dirPaths.add(path);
                        }
                      }
                    }

                    return dirPaths;
                  }

                  @Override
                  public int comparePaths(
                      DirectoryCleaner.PathStats path1, DirectoryCleaner.PathStats path2) {
                    return Long.compare(path1.getCreationMillis(), path2.getCreationMillis());
                  }
                })
            .setMaxTotalSizeBytes(maxLogsSizeBytes)
            .setMaxPathCount(maxLogsDirectories)
            .setMinAmountOfEntriesToKeep(minAmountOfLogsToKeep)
            .build();

    return new DirectoryCleaner(cleanerArgs);
  }
}
