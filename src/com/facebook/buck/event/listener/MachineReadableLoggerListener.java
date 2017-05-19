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

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_EXIT_CODE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_INVOCATION_INFO;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_PERFTIMES_COMPLETE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_PERFTIMES_UPDATE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_UPLOAD_TO_CACHE_STATS;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.CacheUploadInfo;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.autosparse.AutoSparseStateEvents;
import com.facebook.buck.util.versioncontrol.VersionControlStatsEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;
import com.google.common.eventbus.Subscribe;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MachineReadableLoggerListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(MachineReadableLoggerListener.class);

  private static final byte[] NEWLINE = "\n".getBytes(Charsets.UTF_8);
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  private final InvocationInfo info;
  private final ExecutorService executor;
  private final ProjectFilesystem filesystem;
  private final ObjectWriter objectWriter;
  private BufferedOutputStream outputStream;

  // Values to be written in the end of the log.
  private OptionalInt exitCode = OptionalInt.empty();

  // Cache upload statistics
  private AtomicInteger cacheUploadSuccessCount = new AtomicInteger();
  private AtomicInteger cacheUploadFailureCount = new AtomicInteger();

  public MachineReadableLoggerListener(
      InvocationInfo info, ProjectFilesystem filesystem, ExecutorService executor)
      throws FileNotFoundException {
    this.info = info;
    this.filesystem = filesystem;
    this.executor = executor;

    this.objectWriter =
        ObjectMappers.legacyCreate()
            .copy()
            .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
            .writerWithView(JsonViews.MachineReadableLog.class);

    this.outputStream =
        new BufferedOutputStream(
            new FileOutputStream(getLogFilePath().toFile(), /* append */ true));

    writeToLog(PREFIX_INVOCATION_INFO, info);
  }

  @Subscribe
  public void versionControlStats(VersionControlStatsEvent versionControlStatsEvent) {
    writeToLog("SourceControlInformation", versionControlStatsEvent);
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started event) {
    writeToLog("ParseStarted", event);
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished event) {
    writeToLog("ParseFinished", event);
  }

  @Subscribe
  public void buildRuleEventFinished(BuildRuleEvent.Finished event) {
    writeToLog("BuildRuleEvent.Finished", event);
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    exitCode = OptionalInt.of(event.getExitCode());
  }

  @Subscribe
  public synchronized void commandInterrupted(CommandEvent.Interrupted event) {
    exitCode = OptionalInt.of(event.getExitCode());
  }

  @Subscribe
  public synchronized void watchmanFileCreation(WatchmanStatusEvent.FileCreation event) {
    writeToLog("FileCreate", event);
  }

  @Subscribe
  public synchronized void watchmanFileDeletion(WatchmanStatusEvent.FileDeletion event) {
    writeToLog("FileDelete", event);
  }

  @Subscribe
  public void watchmanOverflow(WatchmanStatusEvent.Overflow event) {
    writeToLog("WatchmanOverflow", event);
  }

  @Subscribe
  public synchronized void symlinkInvalidation(ParsingEvent.SymlinkInvalidation event) {
    writeToLog("SymlinkInvalidation", event);
  }

  @Subscribe
  public synchronized void environmentalChange(ParsingEvent.EnvVariableChange event) {
    writeToLog("EnvChange", event);
  }

  @Subscribe
  public synchronized void timePerfStatsEvent(PerfTimesEventListener.PerfTimesEvent event) {
    writeToLog(
        event instanceof PerfTimesEventListener.PerfTimesEvent.Complete
            ? PREFIX_PERFTIMES_COMPLETE
            : PREFIX_PERFTIMES_UPDATE,
        event);
  }

  @Subscribe
  public synchronized void autosparseSparseRefreshStarted(
      AutoSparseStateEvents.SparseRefreshStarted event) {
    writeToLog("Autosparse.SparseRefreshStarted", event);
  }

  @Subscribe
  public synchronized void autosparseSparseRefreshFinished(
      AutoSparseStateEvents.SparseRefreshFinished event) {
    writeToLog("Autosparse.SparseRefreshFinished", event);
  }

  @Subscribe
  public synchronized void autosparseSparseRefreshFailed(
      AutoSparseStateEvents.SparseRefreshFailed event) {
    writeToLog("Autosparse.SparseRefreshFailed", event);
  }

  @Subscribe
  public void onArtifactCacheEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      if (event.getStoreData().wasStoreSuccessful().orElse(false)) {
        cacheUploadSuccessCount.incrementAndGet();
      } else {
        cacheUploadFailureCount.incrementAndGet();
      }
    }
  }

  private Path getLogFilePath() {
    return filesystem
        .resolve(info.getLogDirectoryPath())
        .resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME);
  }

  private void writeToLog(final String prefix, final Object obj) {
    executor.submit(() -> writeToLogImpl(prefix, obj));
  }

  private void writeToLogImpl(String prefix, Object obj) {
    try {
      byte[] serializedObj = objectWriter.writeValueAsBytes(obj);
      outputStream.write((prefix + " ").getBytes(Charsets.UTF_8));
      outputStream.write(serializedObj);
      outputStream.write(NEWLINE);
      outputStream.flush();
    } catch (JsonProcessingException e) {
      LOG.warn("Failed to process json for event type: %s ", prefix);
    } catch (IOException e) {
      LOG.debug("Failed to write to %s", BuckConstant.BUCK_MACHINE_LOG_FILE_NAME, e);
    }
  }

  @Override
  public void outputTrace(BuildId buildId) throws InterruptedException {
    // IMPORTANT: logging the ExitCode must happen on the executor, otherwise random
    // log lines will be overwritten as outputStream access is not thread safe.
    @SuppressWarnings("unused")
    Future<?> unused =
        executor.submit(
            () -> {
              try {
                writeToLogImpl(
                    PREFIX_UPLOAD_TO_CACHE_STATS,
                    CacheUploadInfo.of(cacheUploadSuccessCount, cacheUploadFailureCount));

                outputStream.write(
                    String.format(PREFIX_EXIT_CODE + " {\"exitCode\":%d}", exitCode.orElse(-1))
                        .getBytes(Charsets.UTF_8));

                outputStream.close();
              } catch (IOException e) {
                LOG.warn("Failed to close output stream.");
              }
            });
    executor.shutdown();
    // Allow SHUTDOWN_TIMEOUT_SECONDS seconds for already scheduled writeToLog calls
    // to complete.
    if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      String error =
          "Machine readable log failed to complete all jobs within timeout during shutdown";
      LOG.error(error);
    }
  }
}
