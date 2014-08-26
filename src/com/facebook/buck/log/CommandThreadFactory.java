/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.facebook.buck.util.concurrent.MoreExecutors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

/**
 * A ThreadFactory which associates created threads with the same command associated with
 * the thread which creates the CommandThreadFactory.
 */
public class CommandThreadFactory implements ThreadFactory {

  private final ThreadFactory threadFactory;
  private final ConcurrentMap<Long, String> threadIdToCommandId;
  @Nullable private final String commandId;

  public CommandThreadFactory(String threadName) {
    this(
        new MoreExecutors.NamedThreadFactory(threadName),
        GlobalState.THREAD_ID_TO_COMMAND_ID);
  }

  @VisibleForTesting
  CommandThreadFactory(
      ThreadFactory threadFactory,
      ConcurrentMap<Long, String> threadIdToCommandId) {
    this.threadFactory = Preconditions.checkNotNull(threadFactory);
    this.threadIdToCommandId = Preconditions.checkNotNull(threadIdToCommandId);

    // This might be null in test environments which bypass `Main.runMainThenExit`.
    commandId = this.threadIdToCommandId.get(Thread.currentThread().getId());
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread newThread = threadFactory.newThread(r);
    if (commandId != null) {
      // TODO(#4993059): We need to clean this up when the thread exits
      // and check that this isn't overwriting an old value.
      threadIdToCommandId.put(newThread.getId(), commandId);
    }
    return newThread;
  }
}
