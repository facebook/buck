/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.io.watchman;

import static com.facebook.buck.util.concurrent.MostExecutors.newSingleThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.bser.BserDeserializer;
import com.facebook.buck.util.bser.BserSerializer;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class WatchmanTransportClient implements WatchmanClient, AutoCloseable {

  private static final Logger LOG = Logger.get(WatchmanTransportClient.class);
  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final ListeningExecutorService listeningExecutorService;
  private final Clock clock;
  private final Transport transport;
  private final Console console;
  private final BserSerializer bserSerializer;
  private final BserDeserializer bserDeserializer;

  private boolean disabledWarningShown = false;

  public WatchmanTransportClient(Console console, Clock clock, Transport transport) {
    this.listeningExecutorService = listeningDecorator(newSingleThreadExecutor("Watchman"));
    this.console = console;
    this.clock = clock;
    this.transport = transport;
    this.bserSerializer = new BserSerializer();
    this.bserDeserializer = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED);
  }

  @Override
  public Optional<Map<String, Object>> queryWithTimeout(long timeoutNanos, Object... query)
      throws IOException, InterruptedException {
    return queryListWithTimeout(timeoutNanos, ImmutableList.copyOf(query));
  }

  private Optional<Map<String, Object>> queryListWithTimeout(long timeoutNanos, List<Object> query)
      throws IOException, InterruptedException {
    ListenableFuture<Optional<Map<String, Object>>> future =
        listeningExecutorService.submit(() -> sendWatchmanQuery(query));
    try {
      long startTimeNanos = clock.nanoTime();
      Optional<Map<String, Object>> result =
          waitForQueryNotifyingUserIfSlow(future, timeoutNanos, POLL_TIME_NANOS, query);
      long elapsedNanos = clock.nanoTime() - startTimeNanos;
      LOG.debug("Query %s returned in %d ms", query, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
      return result;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void close() throws IOException {
    LOG.debug("Closing Watchman transport.");
    transport.close();
    listeningExecutorService.shutdown();
  }

  private synchronized void showDisabledWarning(long timeoutNanos) {
    if (disabledWarningShown) {
      return;
    }
    if (console.getVerbosity().isSilent()) {
      return;
    }
    if (timeoutNanos < 0) {
      timeoutNanos = 0;
    }
    disabledWarningShown = true;
    console
        .getStdErr()
        .getRawStream()
        .format(
            "Timed out after %d sec waiting for watchman query. Disabling watchman.\n",
            TimeUnit.NANOSECONDS.toSeconds(timeoutNanos));
  }

  private Optional<Map<String, Object>> waitForQueryNotifyingUserIfSlow(
      ListenableFuture<Optional<Map<String, Object>>> future,
      long timeoutNanos,
      long pollTimeNanos,
      List<Object> query)
      throws InterruptedException, ExecutionException {
    long queryStartNanos = clock.nanoTime();
    try {
      return future.get(Math.min(timeoutNanos, pollTimeNanos), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      long remainingNanos = timeoutNanos - (clock.nanoTime() - queryStartNanos);
      if (remainingNanos > 0) {
        LOG.debug("Waiting for Watchman query [%s]...", query);
        if (!console.getVerbosity().isSilent()) {
          console.getStdErr().getRawStream().format("Waiting for watchman query...\n");
        }
        try {
          return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException te) {
          LOG.debug("Timed out");
        }
      }
      LOG.warn(
          "Watchman did not respond within %d ms, disabling.",
          TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
      showDisabledWarning(timeoutNanos);
      return Optional.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> sendWatchmanQuery(List<Object> query) throws IOException {
    LOG.debug("Sending query: %s", query);
    bserSerializer.serializeToStream(query, transport.getOutputStream());
    Object response = bserDeserializer.deserializeBserValue(transport.getInputStream());
    LOG.verbose("Got response: %s", response);
    Map<String, Object> responseMap = (Map<String, Object>) response;
    if (responseMap == null) {
      LOG.error("Unrecognized Watchman response: %s", response);
      return Optional.empty();
    }
    return Optional.of(responseMap);
  }
}
