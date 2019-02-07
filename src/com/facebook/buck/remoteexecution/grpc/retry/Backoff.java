/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.remoteexecution.grpc.retry;

/** Common backoff strategies to apply for retries */
public class Backoff {
  private Backoff() {}

  /** Policy for specifying custom backoff behavior for retries. */
  public interface Strategy {
    /**
     * Return the amount of time to wait in milliseconds before attempting to retry an operation.
     *
     * @param n The retry number, i.e. number of attempts - 1.
     * @return Time to wait in milliseconds until the next attempt
     */
    long getDelayMilliseconds(int n);
  }

  public static Strategy constant(final long delayMilliseconds) {
    return n -> delayMilliseconds;
  }

  public static Strategy exponential(
      final long initialDelayMilliseconds, final long maxDelayMilliseconds) {
    return n -> Math.min(maxDelayMilliseconds, (1 << n) * initialDelayMilliseconds);
  }
}
