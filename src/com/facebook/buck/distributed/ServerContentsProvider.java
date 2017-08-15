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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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

  private final Object multiFetchLock = new Object();

  @GuardedBy("multiFetchLock")
  private List<String> hashCodesToFetch;

  @GuardedBy("multiFetchLock")
  private CompletableFuture<Map<String, byte[]>> multiFetchFuture;

  private ScheduledFuture<?> scheduledBufferProcessor;

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      Optional<Long> multiFetchBufferPeriodMs,
      Optional<Integer> multiFetchBufferMaxSize) {
    this(
        service,
        networkScheduler,
        multiFetchBufferPeriodMs.orElse(MULTI_FETCH_BUFFER_PERIOD_MS),
        multiFetchBufferMaxSize.orElse(MULTI_FETCH_BUFFER_MAX_SIZE));
  }

  public ServerContentsProvider(
      DistBuildService service,
      ScheduledExecutorService networkScheduler,
      long multiFetchBufferPeriodMs,
      int multiFetchBufferMaxSize) {
    this.service = service;
    this.multiFetchBufferMaxSize = multiFetchBufferMaxSize;

    synchronized (multiFetchLock) {
      hashCodesToFetch = new ArrayList<>(multiFetchBufferMaxSize);
      multiFetchFuture = new CompletableFuture<>();
    }

    scheduledBufferProcessor =
        networkScheduler.scheduleAtFixedRate(
            () -> this.processFileBuffer(false),
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

  private void processFileBuffer(boolean onlyIfBufferIsFull) {
    List<String> hashCodes;
    CompletableFuture<Map<String, byte[]>> resultFuture;

    synchronized (multiFetchLock) {
      if (onlyIfBufferIsFull && hashCodesToFetch.size() < multiFetchBufferMaxSize) {
        return;
      }

      if (hashCodesToFetch.isEmpty()) {
        return;
      }

      hashCodes = hashCodesToFetch;
      hashCodesToFetch = new ArrayList<>(multiFetchBufferMaxSize);
      resultFuture = multiFetchFuture;
      multiFetchFuture = new CompletableFuture<>();
    }

    try {
      resultFuture.complete(service.multiFetchSourceFiles(hashCodes));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @VisibleForTesting
  Future<byte[]> fetchFileContentsAsync(BuildJobStateFileHashEntry entry) {
    Preconditions.checkState(
        entry.isSetHashCode(), String.format("File hash missing for file [%s]", entry.getPath()));

    Future<byte[]> future;
    synchronized (multiFetchLock) {
      hashCodesToFetch.add(entry.getHashCode());
      future =
          multiFetchFuture.thenApply(
              resultMap -> Preconditions.checkNotNull(resultMap).get(entry.getHashCode()));
    }

    // If the buffer has maxed out, fetch right away.
    processFileBuffer(true);
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
