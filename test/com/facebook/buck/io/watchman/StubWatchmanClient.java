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
import java.util.Map;

/**
 * A {@link com.facebook.buck.io.watchman.WatchmanClient} that simply returns a value passed in a
 * constructor.
 */
public class StubWatchmanClient implements WatchmanClient {

  private final Either<Map<String, Object>, WatchmanClient.Timeout> result;

  public StubWatchmanClient(Either<Map<String, Object>, WatchmanClient.Timeout> result) {
    this.result = result;
  }

  @Override
  public Either<Map<String, Object>, WatchmanClient.Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeoutNanos, WatchmanQuery query) {
    return result;
  }

  @Override
  public void close() {}
}
