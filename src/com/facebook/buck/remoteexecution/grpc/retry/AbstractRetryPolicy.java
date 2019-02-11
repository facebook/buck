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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.immutables.value.Value;

/**
 * Immutable policy describing how requests should be retried in terms of delay between retries, the
 * executor to use, and maximum number of retries.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractRetryPolicy {

  @Value.Default
  public int getMaxRetries() {
    return 2;
  }

  @Value.Default
  public Backoff.Strategy getBackoffStrategy() {
    return Backoff.constant(0);
  }

  @Value.Default
  public Runnable getBeforeRetry() {
    return () -> {};
  }

  @Value.Default
  public boolean getRestartAllStreamingCalls() {
    return false;
  }

  @Value.Default
  public ScheduledExecutorService getExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("retryer-%s").setDaemon(true).build());
  }
}
