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

import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/** Watchman client implementation which reconnects on error or timeout. */
class ReconnectingWatchmanClient implements WatchmanClient {

  /** Provider for the underlying lower-level, non-reconnecting watchman. */
  interface UnderlyingWatchmanOpener {
    WatchmanClient open() throws IOException;
  }

  private final UnderlyingWatchmanOpener underlyingWatchmanOpener;

  @Nullable private WatchmanClient underlying;

  ReconnectingWatchmanClient(UnderlyingWatchmanOpener underlyingWatchmanOpener) {
    this.underlyingWatchmanOpener = underlyingWatchmanOpener;
  }

  private final AtomicBoolean running = new AtomicBoolean();

  @Override
  public Either<Map<String, Object>, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
      throws IOException, InterruptedException {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("ReconnectingWatchmanClient is single-threaded");
    }
    try {
      return queryWithTimeoutInner(timeoutNanos, warnTimeNanos, query);
    } finally {
      running.set(false);
    }
  }

  private Either<Map<String, Object>, Timeout> queryWithTimeoutInner(
      long timeoutNanos, long pollingTimeNanos, WatchmanQuery query)
      throws IOException, InterruptedException {
    if (underlying == null) {
      // TODO(nga): issue debug-status query on reconnect
      //  as suggested here https://fburl.com/fml0rdzn
      underlying = underlyingWatchmanOpener.open();
    }

    boolean finishedSuccessfully = false;
    try {
      Either<Map<String, Object>, Timeout> result =
          underlying.queryWithTimeout(timeoutNanos, pollingTimeNanos, query);
      finishedSuccessfully = result.isLeft();
      return result;
    } finally {
      if (!finishedSuccessfully) {
        close();
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (underlying != null) {
      underlying.close();
      underlying = null;
    }
  }

  @Nullable
  @VisibleForTesting
  WatchmanClient getUnderlying() {
    return underlying;
  }
}
