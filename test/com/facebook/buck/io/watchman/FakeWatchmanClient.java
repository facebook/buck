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

/** Fake implementation of {@link com.facebook.buck.io.watchman.WatchmanClient} for tests. */
public class FakeWatchmanClient implements WatchmanClient {
  private final long queryElapsedTimeNanos;
  private final Map<WatchmanQuery, Map<String, Object>> queryResults;
  private final Exception exceptionToThrow;

  public FakeWatchmanClient(
      long queryElapsedTimeNanos, Map<WatchmanQuery, Map<String, Object>> queryResults) {
    this(queryElapsedTimeNanos, queryResults, null);
  }

  public FakeWatchmanClient(
      long queryElapsedTimeNanos,
      Map<WatchmanQuery, Map<String, Object>> queryResults,
      Exception exceptionToThrow) {
    this.queryElapsedTimeNanos = queryElapsedTimeNanos;
    this.queryResults = queryResults;
    this.exceptionToThrow = exceptionToThrow;
  }

  @Override
  public Either<Map<String, Object>, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
      throws InterruptedException, IOException {
    Map<String, Object> result = queryResults.get(query);
    if (result == null) {
      throw new RuntimeException(
          String.format("Could not find results for query %s in %s", query, queryResults.keySet()));
    }
    if (queryElapsedTimeNanos > timeoutNanos) {
      return Either.ofRight(Timeout.INSTANCE);
    }
    if (exceptionToThrow != null) {
      if (exceptionToThrow instanceof IOException) {
        throw (IOException) exceptionToThrow;
      } else if (exceptionToThrow instanceof InterruptedException) {
        throw (InterruptedException) exceptionToThrow;
      } else {
        throw new RuntimeException("Invalid exception");
      }
    }
    return Either.ofLeft(result);
  }

  @Override
  public void close() {}
}
