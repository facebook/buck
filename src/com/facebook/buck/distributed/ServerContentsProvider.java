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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

public class ServerContentsProvider implements FileContentsProvider {

  private static final Logger LOG = Logger.get(ServerContentsProvider.class);

  private static final long MULTI_FETCH_BUFFER_PERIOD_MS = 25;
  private static final int MULTI_FETCH_BUFFER_MAX_SIZE = 40;

  private final DistBuildService service;
  private final int multiFetchBufferMaxSize;
  private final FileMaterializationStatsTracker statsTracker;
  private final ListeningExecutorService networkThreadPool;

  private final Object multiFetchLock = new Object();

  @GuardedBy("multiFetchLock")
  private Set<String> hashCodesToFetch;

  @GuardedBy("multiFetchLock")
  private SettableFuture<Map<String, byte[]>> multiFetchFuture;

  private ScheduledFuture<?> scheduledBufferProcessor;

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      ListeningExecutorService networkThreadPool,
      FileMaterializationStatsTracker statsTracker,
      Optional<Long> multiFetchBufferPeriodMs,
      OptionalInt multiFetchBufferMaxSize) {
    this(
        service,
        networkScheduler,
        networkThreadPool,
        statsTracker,
        multiFetchBufferPeriodMs.orElse(MULTI_FETCH_BUFFER_PERIOD_MS),
        multiFetchBufferMaxSize.orElse(MULTI_FETCH_BUFFER_MAX_SIZE));
  }

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      ListeningExecutorService networkThreadPool,
      FileMaterializationStatsTracker statsTracker,
      long multiFetchBufferPeriodMs,
      int multiFetchBufferMaxSize) {
    this.service = service;
    this.multiFetchBufferMaxSize = multiFetchBufferMaxSize;
    this.statsTracker = statsTracker;
    this.networkThreadPool = networkThreadPool;

    synchronized (multiFetchLock) {
      hashCodesToFetch = new HashSet<>();
      multiFetchFuture = SettableFuture.create();
    }

    scheduledBufferProcessor =
        networkScheduler.scheduleAtFixedRate(
            this::makePeriodicMultiFetchRequest,
            0,
            multiFetchBufferPeriodMs,
            TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    if (scheduledBufferProcessor.isCancelled() || scheduledBufferProcessor.isDone()) {
      return;
    }

    if (!scheduledBufferProcessor.cancel(true)) {
      // If the cancel failed, it must be some error,
      // otherwise this future never completes on its own.
      LOG.error("Unable to cancel scheduled task for processing the multi-fetch file buffer.");
    }
  }

  /** Blocking call */
  private void makePeriodicMultiFetchRequest() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    int numFilesFetched = this.processFileBuffer(false);
    long elapsedMs = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
    if (numFilesFetched > 0) {
      this.statsTracker.recordPeriodicCasMultiFetch(elapsedMs);
    }
  }

  /** Blocking call */
  private void makeMultiFetchRequestIfBufferIsFull() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    int numFilesFetched = this.processFileBuffer(true);
    long elapsedMs = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
    if (numFilesFetched > 0) {
      statsTracker.recordFullBufferCasMultiFetch(elapsedMs);
    }
  }

  /** Blocking call */
  private int processFileBuffer(boolean onlyIfBufferIsFull) {
    Set<String> hashCodes;
    SettableFuture<Map<String, byte[]>> resultFuture;

    synchronized (multiFetchLock) {
      if (onlyIfBufferIsFull && hashCodesToFetch.size() < multiFetchBufferMaxSize) {
        return 0;
      }

      if (hashCodesToFetch.isEmpty()) {
        return 0;
      }

      hashCodes = hashCodesToFetch;
      hashCodesToFetch = new HashSet<>();
      resultFuture = multiFetchFuture;
      multiFetchFuture = SettableFuture.create();
    }

    try {
      LOG.info(
          "Fetching [%d] source files from the CAS (%s).",
          hashCodes.size(), onlyIfBufferIsFull ? "buffer was full" : "scheduled");
      resultFuture.set(service.multiFetchSourceFiles(hashCodes));
    } catch (IOException e) {
      LOG.error(e);
      resultFuture.setException(e);
      return 0;
    }

    return hashCodes.size();
  }

  private ListenableFuture<byte[]> scheduleFileToBeFetched(BuildJobStateFileHashEntry entry) {
    Preconditions.checkState(
        entry.isSetSha1(), String.format("File hash missing for file [%s].", entry.getPath()));

    ListenableFuture<byte[]> future;
    synchronized (multiFetchLock) {
      LOG.verbose(
          "Scheduling file to be fetched from the CAS: [%s] (SHA1: %s).",
          entry.getPath(), entry.getSha1());
      hashCodesToFetch.add(entry.getSha1());
      future =
          Futures.transform(
              multiFetchFuture,
              resultMap -> Preconditions.checkNotNull(resultMap).get(entry.getSha1()));
    }

    return future;
  }

  private boolean writeFileContentsToPath(byte[] fileContents, Path targetAbsPath) {
    try (OutputStream outputStream = InlineContentsProvider.newOutputStream(targetAbsPath)) {
      outputStream.write(fileContents);
      return true;
    } catch (IOException e) {
      LOG.error(e, "Unexpected error in writing file contents: [%s]", e.getMessage());
    }
    return false;
  }

  @Override
  @SuppressWarnings("CheckReturnValue")
  public ListenableFuture<Boolean> materializeFileContentsAsync(
      BuildJobStateFileHashEntry entry, Path targetAbsPath) {

    ListenableFuture<byte[]> fileFuture = scheduleFileToBeFetched(entry);
    // If the buffer is full, make a multi-fetch request using the thread pool.
    // Don't block the calling thread.
    networkThreadPool.submit(this::makeMultiFetchRequestIfBufferIsFull);

    return Futures.transform(
        fileFuture, (byte[] fileContents) -> writeFileContentsToPath(fileContents, targetAbsPath));
  }
}
