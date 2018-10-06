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

package com.facebook.buck.util.network.offline;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.network.ScribeLogger;
import com.fasterxml.jackson.core.JsonFactory;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

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

  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final long CLUSTER_DISPATCH_SIZE = 1024 * 1024;
  private static final int KILO = 1024;
  private static final Logger LOG = Logger.get(OfflineScribeLogger.class);
  // Timeout used when submitting data read from offline logs.
  private static final int LOG_TIMEOUT = 10;
  private static final TimeUnit LOG_TIMEOUT_UNIT = TimeUnit.SECONDS;
  private static final String COUNTERS_CATEGORY = "buck_offline_logs_stats";

  private final ScribeLogger scribeLogger;
  private final ImmutableList<String> blacklistCategories;
  private final int maxScribeOfflineLogsBytes;
  private final ProjectFilesystem filesystem;

  @Nullable private BufferedOutputStream logFileStoreStream;
  private boolean storingAvailable;
  private int bytesStoredSoFar;
  private volatile boolean startedStoring;
  private volatile boolean startedClosing;
  private final Path logDir;
  private final Path newLogPath;
  private final AtomicBoolean startedSendingStored;
  private final IntegerCounter totalLinesResent;
  private final IntegerCounter totalBytesResent;
  private final IntegerCounter logfilesResent;

  // A set of categories that have reported an error so we do not double-report it.
  private Set<String> categoriesReportedAnError;

  public OfflineScribeLogger(
      ScribeLogger scribeLogger,
      ImmutableList<String> blacklistCategories,
      int maxScribeOfflineLogsKB,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      BuildId buildId) {
    Objects.requireNonNull(scribeLogger);
    Objects.requireNonNull(blacklistCategories);
    Preconditions.checkArgument(maxScribeOfflineLogsKB > 0);
    Objects.requireNonNull(projectFilesystem);
    Objects.requireNonNull(buckEventBus);
    Objects.requireNonNull(buildId);

    this.scribeLogger = scribeLogger;
    this.blacklistCategories = blacklistCategories;
    this.maxScribeOfflineLogsBytes = KILO * maxScribeOfflineLogsKB;
    this.filesystem = projectFilesystem;

    this.logFileStoreStream = null;
    this.storingAvailable = true;
    this.bytesStoredSoFar = 0;
    this.startedStoring = false;
    this.startedClosing = false;
    this.logDir = projectFilesystem.getBuckPaths().getOfflineLogDir();
    this.newLogPath =
        projectFilesystem.resolve(logDir.resolve(LOGFILE_PREFIX + buildId + LOGFILE_SUFFIX));
    this.categoriesReportedAnError = Sets.newConcurrentHashSet();

    this.startedSendingStored = new AtomicBoolean(false);
    this.totalLinesResent =
        new IntegerCounter(COUNTERS_CATEGORY, "total_lines_resent", ImmutableMap.of());
    this.totalBytesResent =
        new IntegerCounter(COUNTERS_CATEGORY, "total_bytes_resent", ImmutableMap.of());
    this.logfilesResent =
        new IntegerCounter(COUNTERS_CATEGORY, "logfiles_resent", ImmutableMap.of());
    buckEventBus.post(
        new CounterRegistry.AsyncCounterRegistrationEvent(
            ImmutableList.of(totalLinesResent, totalBytesResent, logfilesResent)));
  }

  @Override
  public ListenableFuture<Void> log(
      String category, Iterable<String> lines, Optional<Integer> bucket) {
    ListenableFuture<Void> upload = scribeLogger.log(category, lines, bucket);
    Futures.addCallback(
        upload,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            if (startedSendingStored.compareAndSet(false, true) && !startedStoring) {
              sendStoredLogs();
            }
          }

          @Override
          public void onFailure(Throwable t) {
            // Sending should be stopped if we require storing.
            startedStoring = true;

            if (!blacklistCategories.contains(category)) {
              LOG.verbose(t, "Storing Scribe lines from category: %s.", category);

              // Get data to store.
              byte[] scribeData;
              try {
                scribeData =
                    ObjectMappers.WRITER
                        .writeValueAsString(
                            ScribeData.builder()
                                .setCategory(category)
                                .setLines(lines)
                                .setBucket(bucket)
                                .build())
                        .getBytes(Charsets.UTF_8);
              } catch (Exception e) {
                if (categoriesReportedAnError.add(category)) {
                  LOG.error(
                      "Failed generating JSON to store for category: %s: %s.",
                      category, e.getMessage());
                }
                return;
              }

              storeOffline(category, scribeData);
            }
          }
        });

    return upload;
  }

  @Override
  public void close() throws IOException {
    // Request stopping sending as soon as possible.
    startedClosing = true;

    synchronized (this) {
      try {
        if (filesystem.isDirectory(logDir)) {
          deleteOldLogsIfNeeded(maxScribeOfflineLogsBytes - bytesStoredSoFar);
        }
      } catch (Exception e) {
        LOG.error(e, "Failed to cleanup logs.");
      }

      // Store what is left in buffer and close.
      if (logFileStoreStream != null) {
        logFileStoreStream.close();
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
      if (logFileStoreStream == null) {
        // Make sure directory exists.
        filesystem.mkdirs(logDir);
        logFileStoreStream =
            new BufferedOutputStream(new FileOutputStream(newLogPath.toFile()), BUFFER_SIZE);
      }

      // Write.
      logFileStoreStream.write(scribeData);
      bytesStoredSoFar += scribeData.length;
    } catch (Exception e) {
      storingAvailable = false;
      LOG.error(e, "Failed storing for offline logging for category: %s.", category);
    }
  }

  private synchronized void deleteOldLogsIfNeeded(long maxSizeForOldLogs) throws IOException {
    ImmutableSortedSet<Path> logs =
        filesystem.getMtimeSortedMatchingDirectoryContents(logDir, LOGFILE_PATTERN);

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

  private synchronized void sendStoredLogs() {
    ImmutableSortedSet<Path> logsPaths;
    try {
      if (!filesystem.isDirectory(logDir)) {
        // No logs to submit to Scribe.
        return;
      }
      logsPaths = filesystem.getMtimeSortedMatchingDirectoryContents(logDir, LOGFILE_PATTERN);
    } catch (Exception e) {
      LOG.error(e, "Fetching stored logs list failed.");
      return;
    }

    long totalBytesToSend = 0;

    for (Path logPath : logsPaths) {
      // The iterator (it) below is lazy and we can't close the stream immediately when the values
      // are read because it can do continuous reads while iterating.
      // So, close the stream after the stream and the iterator that uses it are done.
      InputStream logFileStream = null;

      try {
        // Sending should be ceased if storing has been initiated or closing was started.
        if (startedStoring || startedClosing) {
          break;
        }

        // Get iterator.
        Iterator<ScribeData> it;
        File logFile;
        try {
          logFile = logPath.toFile();
          totalBytesToSend += logFile.length();
          if (totalBytesToSend > maxScribeOfflineLogsBytes) {
            LOG.warn(
                "Total size of offline logs exceeds the limit. Ceasing to send them to Scribe.");
            return;
          }

          try {
            logFileStream = new BufferedInputStream(new FileInputStream(logFile), BUFFER_SIZE);
          } catch (FileNotFoundException e) {
            LOG.info(
                e,
                "There was a problem getting stream for logfile: %s. Likely logfile was resent and"
                    + "deleted by a concurrent Buck command.",
                logPath);
            continue;
          }

          it =
              ObjectMappers.READER.readValues(
                  new JsonFactory().createParser(logFileStream), ScribeData.class);
        } catch (Exception e) {
          LOG.error(e, "Failed to initiate reading from: %s. File may be corrupted.", logPath);
          continue;
        }

        // Read and submit.
        int scribeLinesInFile = 0;
        List<ListenableFuture<Void>> logFutures = new LinkedList<>();
        Map<String, CategoryData> logReadData = new HashMap<>();
        try {
          boolean interrupted = false;

          // Read data and build per category clusters - dispatch if needed.
          while (it.hasNext()) {
            if (startedStoring || startedClosing) {
              interrupted = true;
              break;
            }

            ScribeData newData = it.next();
            // Prepare map entry for new data (dispatch old data if needed).
            if (!logReadData.containsKey(newData.getCategory())) {
              logReadData.put(newData.getCategory(), new CategoryData());
            }
            CategoryData categoryData = logReadData.get(newData.getCategory());
            List<String> linesToLog =
                categoryData.getLinesAndReset(Optional.of(CLUSTER_DISPATCH_SIZE));
            if (!linesToLog.isEmpty()) {
              logFutures.add(scribeLogger.log(newData.getCategory(), linesToLog));
            }
            // Add new data to the cluster for the category.
            for (String line : newData.getLines()) {
              categoryData.addLine(line);
              scribeLinesInFile++;
            }
          }

          // Send remaining data from per category clusters.
          if (!interrupted) {
            for (Map.Entry<String, CategoryData> logReadDataEntry : logReadData.entrySet()) {
              if (startedStoring || startedClosing) {
                interrupted = true;
                break;
              }

              List<String> categoryLines =
                  logReadDataEntry.getValue().getLinesAndReset(Optional.empty());
              if (categoryLines.size() > 0) {
                logFutures.add(scribeLogger.log(logReadDataEntry.getKey(), categoryLines));
              }
            }
          }

          if (interrupted) {
            LOG.info(
                "Stopped while sending from offline log (it will not be removed): %s.", logPath);
            logFutures.clear();
            break;
          }

        } catch (Exception e) {
          LOG.error(
              e,
              "Error while reading offline log from: %s. This log will not be removed now. If this "
                  + "error reappears in further runs, the file may be corrupted and should be deleted. ",
              logPath);
          logFutures.clear();
          continue;
        } finally {
          logReadData.clear();
        }

        // Confirm data was successfully sent and remove logfile.
        try {
          Futures.allAsList(logFutures).get(LOG_TIMEOUT, LOG_TIMEOUT_UNIT);
          totalBytesResent.inc(logFile.length());
          totalLinesResent.inc(scribeLinesInFile);
          logfilesResent.inc();
          try {
            filesystem.deleteFileAtPathIfExists(logPath);
          } catch (Exception e) {
            LOG.error(e, "Failed to remove successfully resent offline log. Stopping sending.");
            break;
          }
        } catch (Exception e) {
          LOG.info(
              "Failed to send all data from offline log: %s. Log will not be removed.", logPath);
          // Do not attempt to send data from further logfiles - likely there are network issues.
          break;
        } finally {
          logFutures.clear();
        }
      } finally {
        if (logFileStream != null) {
          try {
            logFileStream.close();
          } catch (IOException e) {
            LOG.error(e, "Could not close log file stream.");
          }
        }
      }
    }
  }

  @ThreadSafe
  private class CategoryData {
    private long linesBytes = 0;
    private List<String> lines = new LinkedList<>();

    public synchronized List<String> getLinesAndReset(Optional<Long> minimumSize) {
      if (minimumSize.isPresent() && linesBytes < minimumSize.get()) {
        return new LinkedList<>();
      }
      List<String> toReturn = lines;
      lines = new LinkedList<>();
      linesBytes = 0;
      return toReturn;
    }

    public synchronized void addLine(String line) {
      lines.add(line);
      linesBytes += line.getBytes(Charsets.UTF_8).length;
    }
  }
}
