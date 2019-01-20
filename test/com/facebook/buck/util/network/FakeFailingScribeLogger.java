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

package com.facebook.buck.util.network;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fake implementation of {@link ScribeLogger} which fails to log after the first attempt (which
 * succeeds).
 */
public final class FakeFailingScribeLogger extends ScribeLogger {

  private AtomicBoolean failLogging = new AtomicBoolean(false);

  @Override
  public ListenableFuture<Void> log(
      String category, Iterable<String> lines, Optional<Integer> bucket) {
    return failLogging.compareAndSet(false, true)
        ? Futures.immediateFuture(null)
        : Futures.immediateFailedFuture(new IOException());
  }

  @Override
  public void close() {}
}
