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

package com.facebook.buck.cli;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/** A background task in the buck daemon that kills the daemon after it's been idle for a while. */
@ThreadSafe
final class IdleKiller {

  interface CommandExecutionScope extends AutoCloseable {
    @Override
    void close();
  }

  private final ScheduledExecutorService scheduledExecutorService;
  private final Duration idleKillDelay;
  private final Runnable killTask;

  private @Nullable ScheduledFuture<?> idleKillTask;
  private int activeCommands;

  IdleKiller(
      ScheduledExecutorService scheduledExecutorService,
      Duration idleKillDelay,
      Runnable killTask) {
    this.scheduledExecutorService = scheduledExecutorService;
    this.idleKillDelay = idleKillDelay;
    this.killTask = killTask;
  }

  synchronized CommandExecutionScope newCommandExecutionScope() {
    activeCommands++;
    clearIdleKillTask(); // Cancel any pending kill tasks as we are no longer idle.
    return () -> {
      synchronized (IdleKiller.this) {
        activeCommands--;
        if (activeCommands == 0) { // Schedule a kill task since we are once again idle.
          setIdleKillTask();
        }
      }
    };
  }

  /** Clear any existing kill tasks. */
  private void clearIdleKillTask() {
    if (idleKillTask != null) {
      idleKillTask.cancel(false);
      idleKillTask = null;
    }
  }

  /** Clear any existing kill tasks and set up a new kill task to fire after idle delay. */
  private void setIdleKillTask() {
    clearIdleKillTask();
    idleKillTask =
        scheduledExecutorService.schedule(
            killTask, idleKillDelay.toMillis(), TimeUnit.MILLISECONDS);
  }
}
