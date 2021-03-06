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
import java.io.IOException;
import java.util.Map;

/**
 * Watchman interface.
 *
 * <p>This class is NOT thread-safe.
 */
public interface WatchmanClient extends AutoCloseable {
  /** Marker for query timeout. */
  enum Timeout {
    INSTANCE,
  }

  /** Perform the query. */
  Either<Map<String, Object>, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
      throws IOException, InterruptedException;

  @Override
  void close() throws IOException;
}
