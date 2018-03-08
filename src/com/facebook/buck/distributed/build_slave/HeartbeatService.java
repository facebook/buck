/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Makes sure the clients send constant heartbeats to the servers to report healthy. */
public class HeartbeatService implements Closeable {

  private static final Logger LOG = Logger.get(HeartbeatService.class);
  private static final long MAX_WAIT_TIMEOUT_MILLIS = 5000;

  private final ScheduledExecutorService service;
  private final long intervalMillis;

  /** Called at a regular interval to perform heartbeat operations. */
  public interface HeartbeatCallback {

    void runHeartbeat() throws IOException;
  }

  public HeartbeatService(long intervalMillis) {
    this(intervalMillis, createNewScheduleExecutor());
  }

  private static ScheduledExecutorService createNewScheduleExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        new CommandThreadFactory(HeartbeatService.class.getSimpleName()));
  }

  /** This service will take care of shutting down the Executor on close(). */
  @VisibleForTesting
  HeartbeatService(long intervalMillis, ScheduledExecutorService service) {
    this.intervalMillis = intervalMillis;
    this.service = service;
  }

  /** Add callback to be regularly called by the HeartbeatService and perform health checks. */
  public Closeable addCallback(String checkName, HeartbeatCallback callback) {
    ScheduledFuture<?> future =
        this.service.scheduleAtFixedRate(
            wrapHeartbeatCallback(callback), 0, intervalMillis, TimeUnit.MILLISECONDS);

    return new Closeable() {
      @Override
      public void close() {
        if (!future.cancel(true)) {
          String msg =
              String.format(
                  "Failed to stop the heartbeart service for [%s] within [%d millis].",
                  checkName, MAX_WAIT_TIMEOUT_MILLIS);
          LOG.error(msg);
          throw new RuntimeException(msg);
        }
      }
    };
  }

  @Override
  public void close() {
    LOG.info("Shutting down heartbeat service.");
    this.service.shutdown();
  }

  private static Runnable wrapHeartbeatCallback(HeartbeatCallback callback) {
    return new Runnable() {
      private long failedHeartbeats = 0;

      @Override
      public void run() {
        try {
          callback.runHeartbeat();
        } catch (Throwable e) {
          // We want to make sure this always keeps going no matter what.
          LOG.warn(e, "The total number of failed heartbeat calls is [%d].", ++failedHeartbeats);
        }
      }
    };
  }
}
