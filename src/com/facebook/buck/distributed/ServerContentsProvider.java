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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.concurrent.GuardedBy;

public class ServerContentsProvider implements FileContentsProvider {

  private static final Logger LOG = Logger.get(ServerContentsProvider.class);

  private static final long MULTI_FETCH_BUFFER_PERIOD_MS = 100;
  private static final int MULTI_FETCH_BUFFER_MAX_SIZE = 8;
  private static final long SERVER_FETCH_MAX_TIMEOUT_SECONDS = 15;

  private final DistBuildService service;
  private final int multiFetchBufferMaxSize;
  private final FileMaterializationStatsTracker statsTracker;

  private final Object multiFetchLock = new Object();

  @GuardedBy("multiFetchLock")
  private List<String> hashCodesToFetch;

  @GuardedBy("multiFetchLock")
  private SettableFuture<Map<String, byte[]>> multiFetchFuture;

  private ScheduledFuture<?> scheduledBufferProcessor;

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      FileMaterializationStatsTracker statsTracker,
      Optional<Long> multiFetchBufferPeriodMs,
      Optional<Integer> multiFetchBufferMaxSize) {
    this(
        service,
        networkScheduler,
        statsTracker,
        multiFetchBufferPeriodMs.orElse(MULTI_FETCH_BUFFER_PERIOD_MS),
        multiFetchBufferMaxSize.orElse(MULTI_FETCH_BUFFER_MAX_SIZE));
  }

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      FileMaterializationStatsTracker statsTracker,
      long multiFetchBufferPeriodMs,
      int multiFetchBufferMaxSize) {
    this.service = service;
    this.multiFetchBufferMaxSize = multiFetchBufferMaxSize;
    this.statsTracker = statsTracker;

    synchronized (multiFetchLock) {
      hashCodesToFetch = new ArrayList<>(multiFetchBufferMaxSize);
      multiFetchFuture = SettableFuture.create();
    }

    scheduledBufferProcessor =
        networkScheduler.scheduleAtFixedRate(
            () -> {
              Stopwatch stopwatch = Stopwatch.createStarted();
              int numFilesFetched = this.processFileBuffer(false);
              long elapsedMs = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
              if (numFilesFetched > 0) {
                this.statsTracker.recordPeriodicCasMultiFetch(elapsedMs);
              }
            },
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

  private int processFileBuffer(boolean onlyIfBufferIsFull) {
    List<String> hashCodes;
    SettableFuture<Map<String, byte[]>> resultFuture;

    synchronized (multiFetchLock) {
      if (onlyIfBufferIsFull && hashCodesToFetch.size() < multiFetchBufferMaxSize) {
        return 0;
      }

      if (hashCodesToFetch.isEmpty()) {
        return 0;
      }

      hashCodes = hashCodesToFetch;
      hashCodesToFetch = new ArrayList<>(multiFetchBufferMaxSize);
      resultFuture = multiFetchFuture;
      multiFetchFuture = SettableFuture.create();
    }

    try {
      resultFuture.set(service.multiFetchSourceFiles(hashCodes));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return hashCodes.size();
  }

  @VisibleForTesting
  ListenableFuture<byte[]> fetchFileContentsAsync(BuildJobStateFileHashEntry entry) {
    Preconditions.checkState(
        entry.isSetSha1(), String.format("File hash missing for file [%s]", entry.getPath()));

    ListenableFuture<byte[]> future;
    synchronized (multiFetchLock) {
      hashCodesToFetch.add(entry.getSha1());
      future =
          Futures.transform(
              multiFetchFuture,
              resultMap -> Preconditions.checkNotNull(resultMap).get(entry.getSha1()));
    }

    // If the buffer has maxed out, fetch right away.
    Stopwatch stopwatch = Stopwatch.createStarted();
    int numFilesFetched = this.processFileBuffer(true);
    long elapsedMs = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
    if (numFilesFetched > 0) {
      statsTracker.recordFullBufferCasMultiFetch(elapsedMs);
    }
    return future;
  }

  @Override
  public boolean materializeFileContents(BuildJobStateFileHashEntry entry, Path targetAbsPath)
      throws IOException {
    try {
      byte[] fileContents =
          fetchFileContentsAsync(entry).get(SERVER_FETCH_MAX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      try (OutputStream outputStream = InlineContentsProvider.newOutputStream(targetAbsPath)) {
        outputStream.write(fileContents);
      }

    } catch (InterruptedException | ExecutionException e) {
      LOG.error(e, "Unexpected error in fetching source file [%s]", entry.getPath());
    } catch (TimeoutException e) {
      throw new RuntimeException("Timed out while waiting to fetch the file from the server.", e);
    }
    return true;
  }
}
