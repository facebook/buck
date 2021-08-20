/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.watchman;

import static com.facebook.buck.util.concurrent.MostExecutors.newSingleThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.console.EventConsole;
import com.facebook.buck.util.bser.BserDeserializer;
import com.facebook.buck.util.bser.BserSerializer;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

class WatchmanTransportClient implements WatchmanClient, AutoCloseable {

  private static final Logger LOG = Logger.get(WatchmanTransportClient.class);

  private final ListeningExecutorService listeningExecutorService;
  private final Clock clock;
  private final Transport transport;
  private final EventConsole console;
  private final BserSerializer bserSerializer;
  private final BserDeserializer bserDeserializer;

  public WatchmanTransportClient(EventConsole console, Clock clock, Transport transport) {
    this.listeningExecutorService = listeningDecorator(newSingleThreadExecutor("Watchman"));
    this.console = console;
    this.clock = clock;
    this.transport = transport;
    this.bserSerializer = new BserSerializer();
    this.bserDeserializer = new BserDeserializer();
  }

  private final AtomicBoolean running = new AtomicBoolean();

  @Override
  public <R extends WatchmanQueryResp> Either<R, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery<R> query)
      throws IOException, InterruptedException, WatchmanQueryFailedException {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("WatchmanTransportClient is single-threaded");
    }
    try {
      return queryListWithTimeoutAndWarning(timeoutNanos, warnTimeNanos, query);
    } finally {
      running.set(false);
    }
  }

  private <R extends WatchmanQueryResp> Either<R, Timeout> queryListWithTimeoutAndWarning(
      long timeoutNanos, long warnTimeoutNanos, WatchmanQuery<R> query)
      throws IOException, WatchmanQueryFailedException, InterruptedException {
    ListenableFuture<ImmutableMap<String, Object>> future =
        listeningExecutorService.submit(() -> sendWatchmanQuery(query.toProtocolArgs()));
    try {
      long startTimeNanos = clock.nanoTime();
      Either<ImmutableMap<String, Object>, Timeout> result =
          waitForQueryNotifyingUserIfSlow(future, timeoutNanos, warnTimeoutNanos, query);

      if (result.isLeft()) {
        Object error = result.getLeft().get("error");
        if (error != null) {
          // Full error looks like this:
          // ```
          // 14QueryExecError: query failed: synchronization failed: syncToNow:
          // timed out waiting for cookie file to be observed by watcher within 1 milliseconds:
          // Operation timed out
          // ```
          // TODO(nga): watchman does not provide API to get some error code.
          //   We should to rely on error code instead of substring matching.
          //   This code will breaks is some file name contains
          //   "Operation timed out" substring or if watchman changes error message.
          if (error.toString().contains("timed out waiting for cookie")) {
            return Either.ofRight(Timeout.INSTANCE);
          }

          throw new WatchmanQueryFailedException(
              String.format("watchman query %s failed: %s", query.queryDesc(), error),
              WatchmanError.UNCLASSIFIED_REMOTE_ERROR);
        }

        Object warning = result.getLeft().get("warning");
        if (warning != null) {
          // We do a lot of queries, if watchman decides to produce warnings for each of them
          // we should not overflow wherever important warnings are written.
          if (query instanceof WatchmanQuery.Query) {
            LOG.debug("Warning in watchman output of query %s: %s", query.queryDesc(), warning);
          } else {
            LOG.warn("Warning in watchman output of query %s: %s", query.queryDesc(), warning);
          }
          // Warnings are not fatal. Don't panic.
        }
      }

      long elapsedNanos = clock.nanoTime() - startTimeNanos;
      LOG.debug("Query %s returned in %d ms", query, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
      if (result.isLeft()) {
        return Either.ofLeft(query.decodeResponse(result.getLeft()));
      } else {
        return Either.ofRight(result.getRight());
      }
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

  private synchronized void showTimeoutWarning(long timeoutNanos, String whichQuery) {
    LOG.warn(
        "Watchman did not respond to '%s' within %dms.",
        whichQuery, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));

    if (timeoutNanos < 0) {
      timeoutNanos = 0;
    }
    console.warn(
        String.format(
            "Timed out after %ds waiting for watchman query '%s'.",
            TimeUnit.NANOSECONDS.toSeconds(timeoutNanos), whichQuery));
  }

  private Either<ImmutableMap<String, Object>, Timeout> waitForQueryNotifyingUserIfSlow(
      ListenableFuture<ImmutableMap<String, Object>> future,
      long timeoutNanos,
      long warnTimeNanos,
      WatchmanQuery<?> query)
      throws InterruptedException, ExecutionException {
    long queryStartNanos = clock.nanoTime();
    long timeout = Math.min(timeoutNanos, warnTimeNanos);
    try {
      return Either.ofLeft(future.get(timeout, TimeUnit.NANOSECONDS));
    } catch (TimeoutException e) {
      long remainingNanos = timeoutNanos - (clock.nanoTime() - queryStartNanos);
      if (remainingNanos > 0) {
        LOG.debug("Waiting for Watchman query [%s]...", query);
        console.warn(
            String.format(
                "Waiting for watchman query '%s'. %ds passed, timeout: %ds...",
                query.queryDesc(),
                TimeUnit.NANOSECONDS.toSeconds(timeout),
                TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)));
        try {
          ImmutableMap<String, Object> result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
          long queryDurationNanos = clock.nanoTime() - queryStartNanos;
          LOG.debug(
              "Watchman query [%s] finished in %dms",
              query, TimeUnit.NANOSECONDS.toMillis(queryDurationNanos));
          return Either.ofLeft(result);
        } catch (TimeoutException te) {
          LOG.debug("Timed out");
        }
      }
      showTimeoutWarning(timeoutNanos, query.queryDesc());
      return Either.ofRight(Timeout.INSTANCE);
    }
  }

  @SuppressWarnings("unchecked")
  private ImmutableMap<String, Object> sendWatchmanQuery(List<Object> query) throws IOException {
    LOG.verbose("Sending query: %s", query);
    bserSerializer.serializeToStream(query, transport.getOutputStream());
    Object response = bserDeserializer.deserializeBserValue(transport.getInputStream());
    LOG.verbose("Got response: %s", response);
    ImmutableMap<String, Object> responseMap = (ImmutableMap<String, Object>) response;
    Preconditions.checkNotNull(responseMap, "response must not be null");
    return responseMap;
  }
}
