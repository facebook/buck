/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util.concurrent;

import com.google.common.util.concurrent.AbstractListeningExecutorService;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fake implementation of {@link ListeningExecutorService} that can be used for unit tests. In
 * particular, it is designed to capture whether {@link #shutdownNow()} was invoked.
 */
public class FakeListeningExecutorService extends AbstractListeningExecutorService {

  private boolean wasShutdownInvoked = false;
  private boolean wasShutdownNowInvoked = false;

  @Override
  public void shutdown() {
    wasShutdownInvoked = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    wasShutdownNowInvoked = true;
    return null;
  }

  /** @return {@code true} if this object's {@link #shutdownNow()} method was invoked. */
  public boolean wasShutdownNowInvoked() {
    return wasShutdownNowInvoked;
  }

  @Override
  public boolean isShutdown() {
    return wasShutdownInvoked || wasShutdownNowInvoked;
  }

  @Override
  public boolean isTerminated() {
    return wasShutdownInvoked || wasShutdownNowInvoked;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return false;
  }

  /**
   * Because this is a fake, the specified {@link Runnable} is not actually executed (unless this
   * method is overridden).
   */
  @Override
  public void execute(Runnable command) {
  }

}
