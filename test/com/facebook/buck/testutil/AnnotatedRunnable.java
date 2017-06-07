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

package com.facebook.buck.testutil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AnnotatedRunnable implements Runnable {
  private final long initDelay;
  private final long delay;
  private final TimeUnit unit;
  private final Runnable delegate;
  private ScheduledFuture<?> future;

  public AnnotatedRunnable(Runnable delegate, long initDelay, long delay, TimeUnit unit) {
    this.delegate = delegate;
    this.initDelay = initDelay;
    this.delay = delay;
    this.unit = unit;
  }

  public AnnotatedRunnable(Runnable delegate) {
    this(delegate, -1, -1, TimeUnit.SECONDS);
  }

  @Override
  public void run() {
    delegate.run();
  }

  public long getInitDelay() {
    return initDelay;
  }

  public long getDelay() {
    return delay;
  }

  public TimeUnit getUnit() {
    return unit;
  }

  public ScheduledFuture<?> getFuture() {
    return future;
  }

  public void setFuture(ScheduledFuture<?> future) {
    this.future = future;
  }
}
