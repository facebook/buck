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

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_BUILD_FINISHED;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_BUILD_RULE_FINISHED;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_CACHE_STATS;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_EXIT_CODE;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_INVOCATION_INFO;
import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_PERFTIMES;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheCountersSummary;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.PerfTimesStats;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.support.bgtasks.TaskAction;
import com.facebook.buck.support.bgtasks.TaskManagerScope;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.versioncontrol.VersionControlStatsEvent;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class MachineReadableLoggerListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(MachineReadableLoggerListener.class);

  private static final byte[] NEWLINE = "\n".getBytes(Charsets.UTF_8);
  private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

  private final InvocationInfo info;
  private final ExecutorService executor;
  private final ProjectFilesystem filesystem;
  private final ObjectWriter objectWriter;
  private BufferedOutputStream outputStream;

  private final TaskManagerScope managerScope;

  private ConcurrentMap<ArtifactCacheMode, AtomicInteger> cacheModeHits = Maps.newConcurrentMap();
  private ConcurrentMap<ArtifactCacheMode, AtomicInteger> cacheModeErrors = Maps.newConcurrentMap();
  private AtomicInteger cacheMisses = new AtomicInteger(0);
  private AtomicInteger cacheIgnores = new AtomicInteger(0);
  private AtomicInteger localKeyUnchangedHits = new AtomicInteger(0);

  @Nullable private PerfTimesStats latestPerfTimesStats;

  // Values to be written in the end of the log.
  private Optional<ExitCode> exitCode = Optional.empty();

  // Cache upload statistics
  private AtomicInteger cacheUploadSuccessCount = new AtomicInteger();
  private AtomicInteger cacheUploadFailureCount = new AtomicInteger();

  public MachineReadableLoggerListener(
      InvocationInfo info,
      ProjectFilesystem filesystem,
      ExecutorService executor,
      ImmutableSet<ArtifactCacheMode> cacheModes,
      TaskManagerScope managerScope)
      throws FileNotFoundException {
    this.info = info;
    this.filesystem = filesystem;
    this.executor = executor;
    this.managerScope = managerScope;

    for (ArtifactCacheMode mode : cacheModes) {
      cacheModeHits.put(mode, new AtomicInteger(0));
      cacheModeErrors.put(mode, new AtomicInteger(0));
    }

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

  /** @ BuildFinished event handler */
  @Subscribe
  public void buildFinished(BuildEvent.Finished event) {
    writeToLog(PREFIX_BUILD_FINISHED, event);
  }

  @Subscribe
  public void buildRuleEventFinished(BuildRuleEvent.Finished event) {
    writeToLog(PREFIX_BUILD_RULE_FINISHED, event);

    CacheResult cacheResult = event.getCacheResult();
    if (cacheResult.getType().isSuccess()) {
      if (cacheResult.getType() == CacheResultType.LOCAL_KEY_UNCHANGED_HIT) {
        localKeyUnchangedHits.incrementAndGet();
      } else if (cacheResult.getType() == CacheResultType.HIT) {
        cacheResult.cacheMode().ifPresent(mode -> cacheModeHits.get(mode).incrementAndGet());
      } else {
        throw new IllegalArgumentException("Unexpected CacheResult: " + cacheResult);
      }
    } else if (cacheResult.getType() == CacheResultType.ERROR) {
      cacheResult.cacheMode().ifPresent(mode -> cacheModeErrors.get(mode).incrementAndGet());
    } else if (cacheResult.getType() == CacheResultType.IGNORED) {
      cacheIgnores.incrementAndGet();
    } else {
      cacheMisses.incrementAndGet();
    }
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    exitCode = Optional.of(event.getExitCode());
  }

  @Subscribe
  public synchronized void commandInterrupted(CommandEvent.Interrupted event) {
    exitCode = Optional.of(event.getExitCode());
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
    latestPerfTimesStats = event.getPerfTimesStats();
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

  private void writeToLog(String prefix, Object obj) {
    executor.submit(() -> writeToLogImpl(prefix, obj));
  }

  private void writeToLogImpl(String prefix, Object obj) {
    try {
      outputStream.write((prefix + " ").getBytes(Charsets.UTF_8));
      objectWriter.without(Feature.AUTO_CLOSE_TARGET).writeValue(outputStream, obj);
      outputStream.write(NEWLINE);
      outputStream.flush();
    } catch (JsonProcessingException e) {
      LOG.warn("Failed to process json for event type: %s ", prefix);
    } catch (IOException e) {
      LOG.debug("Failed to write to %s", BuckConstant.BUCK_MACHINE_LOG_FILE_NAME, e);
    }
  }

  @Override
  public void close() {
    executor.submit(
        () -> {
          try {
            if (latestPerfTimesStats != null) {
              writeToLogImpl(PREFIX_PERFTIMES, latestPerfTimesStats);
            }
            writeToLogImpl(
                PREFIX_CACHE_STATS,
                CacheCountersSummary.of(
                    cacheModeHits,
                    cacheModeErrors,
                    cacheModeHits.values().stream().mapToInt(AtomicInteger::get).sum(),
                    cacheModeErrors.values().stream().mapToInt(AtomicInteger::get).sum(),
                    cacheMisses.get(),
                    cacheIgnores.get(),
                    localKeyUnchangedHits.get(),
                    cacheUploadSuccessCount,
                    cacheUploadFailureCount));

            outputStream.write(
                String.format(
                        PREFIX_EXIT_CODE + " {\"exitCode\":%d}",
                        exitCode.map(code -> code.getCode()).orElse(-1))
                    .getBytes(Charsets.UTF_8));

            outputStream.close();
          } catch (IOException e) {
            LOG.warn("Failed to close output stream.");
          }
        });
    BackgroundTask<MachineReadableLoggerListenerCloseArgs> task =
        ImmutableBackgroundTask.<MachineReadableLoggerListenerCloseArgs>builder()
            .setAction(new MachineReadableLoggerListenerCloseAction())
            .setActionArgs(MachineReadableLoggerListenerCloseArgs.of(executor))
            .setName("MachineReadableLoggerListener_close")
            .build();
    managerScope.schedule(task);
  }

  /**
   * {@link TaskAction} implementation for {@link MachineReadableLoggerListener}. Waits for output
   * executor to finish and close.
   */
  static class MachineReadableLoggerListenerCloseAction
      implements TaskAction<MachineReadableLoggerListenerCloseArgs> {
    @Override
    public void run(MachineReadableLoggerListenerCloseArgs args) {
      args.getExecutor().shutdown();
      // Allow SHUTDOWN_TIMEOUT_SECONDS seconds for already scheduled writeToLog calls
      // to complete.
      try {
        if (!args.getExecutor().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          String error =
              "Machine readable log failed to complete all jobs within timeout during shutdown";
          LOG.error(error);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Arguments to {@link MachineReadableLoggerListenerCloseAction}. */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractMachineReadableLoggerListenerCloseArgs {
    @Value.Parameter
    public abstract ExecutorService getExecutor();
  }
}
