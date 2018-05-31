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

import com.facebook.buck.util.concurrent.MostExecutors;
import java.util.OptionalInt;
import java.util.concurrent.ThreadFactory;
import javax.annotation.Nullable;

/**
 * A ThreadFactory which associates created threads with the same command associated with the thread
 * which creates the CommandThreadFactory.
 */
public class CommandThreadFactory implements ThreadFactory {

  private final ThreadFactory threadFactory;
  private final CommonThreadFactoryState state;
  @Nullable private final String commandId;
  private final OptionalInt optionalPriority;

  public CommandThreadFactory(String threadName, int threadPriority) {
    this(new MostExecutors.NamedThreadFactory(threadName), OptionalInt.of(threadPriority));
  }

  public CommandThreadFactory(String threadName) {
    this(new MostExecutors.NamedThreadFactory(threadName), OptionalInt.empty());
  }

  public CommandThreadFactory(ThreadFactory threadFactory) {
    this(threadFactory, OptionalInt.empty());
  }

  public CommandThreadFactory(ThreadFactory threadFactory, OptionalInt optionalPriority) {
    this.threadFactory = threadFactory;
    this.state = GlobalStateManager.singleton().getThreadToCommandRegister();

    // This might be null in test environments which bypass `Main.runMainThenExit`.
    this.commandId = state.threadIdToCommandId(Thread.currentThread().getId());
    this.optionalPriority = optionalPriority;
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread newThread = threadFactory.newThread(r);
    if (optionalPriority.isPresent()) {
      newThread.setPriority(optionalPriority.getAsInt());
    }

    if (commandId != null) {
      state.register(newThread.getId(), commandId);
    }

    return newThread;
  }
}
