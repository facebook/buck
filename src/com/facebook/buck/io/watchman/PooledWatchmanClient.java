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
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Thread-safe pool of {@link WatchmanClient}. */
class PooledWatchmanClient implements WatchmanClient {

  /** Provider for the underlying lower-level client. Client must be reconnecting. */
  interface UnderlyingWatchmanOpener {
    WatchmanClient open() throws IOException;
  }

  /** Factory of new clients. */
  private final UnderlyingWatchmanOpener underlyingWatchmanOpener;
  /** Released clients are stored here. */
  private final ConcurrentLinkedQueue<WatchmanClient> pool = new ConcurrentLinkedQueue<>();
  /** Client is closed. */
  private volatile boolean closed = false;

  PooledWatchmanClient(UnderlyingWatchmanOpener underlyingWatchmanOpener) {
    this.underlyingWatchmanOpener = underlyingWatchmanOpener;
  }

  @Override
  public Either<Map<String, Object>, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
      throws IOException, InterruptedException {
    Preconditions.checkState(!closed, "closed");
    WatchmanClient client = pool.poll();

    if (client == null) {
      client = underlyingWatchmanOpener.open();
    }

    try {
      return client.queryWithTimeout(timeoutNanos, warnTimeNanos, query);
    } finally {
      pool.add(client);

      // This request was finished after close was called.
      // So call close again to make sure the client we just released is closed.
      if (closed) {
        closePool();
      }
    }
  }

  public void closePool() throws IOException {
    closed = true;
    WatchmanClient client;
    while ((client = pool.poll()) != null) {
      client.close();
    }
  }

  /** @deprecated */
  @Deprecated
  @Override
  public void close() throws IOException {
    // Client is shared by multiple users, prevent accidental closing
    throw new IllegalStateException("PooledWatchmanClient is not meant to be closed by close()");
  }
}
