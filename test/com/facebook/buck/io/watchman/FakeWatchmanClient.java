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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fake implementation of {@link com.facebook.buck.io.watchman.WatchmanClient} for tests. */
public class FakeWatchmanClient implements WatchmanClient {
  private final long queryElapsedTimeNanos;
  private final Map<? extends List<? extends Object>, ? extends Map<String, ? extends Object>>
      queryResults;
  private final Exception exceptionToThrow;

  public FakeWatchmanClient(
      long queryElapsedTimeNanos,
      Map<? extends List<? extends Object>, ? extends Map<String, ? extends Object>> queryResults) {
    this(queryElapsedTimeNanos, queryResults, null);
  }

  public FakeWatchmanClient(
      long queryElapsedTimeNanos,
      Map<? extends List<? extends Object>, ? extends Map<String, ? extends Object>> queryResults,
      Exception exceptionToThrow) {
    this.queryElapsedTimeNanos = queryElapsedTimeNanos;
    this.queryResults = queryResults;
    this.exceptionToThrow = exceptionToThrow;
  }

  @Override
  public Optional<? extends Map<String, ? extends Object>> queryWithTimeout(
      long timeoutNanos, Object... query) throws InterruptedException, IOException {
    Map<String, ? extends Object> result = queryResults.get(Arrays.asList(query));
    if (result == null) {
      throw new RuntimeException(
          String.format(
              "Could not find results for query %s in %s",
              Arrays.asList(query), queryResults.keySet()));
    }
    if (queryElapsedTimeNanos > timeoutNanos) {
      return Optional.empty();
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
    return Optional.of(result);
  }

  @Override
  public void close() {}
}
