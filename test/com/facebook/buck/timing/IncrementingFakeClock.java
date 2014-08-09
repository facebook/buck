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

package com.facebook.buck.timing;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a fake implementation of a {@link Clock} which increments
 * both {@link #currentTimeMillis()} and {@link #nanoTime()} by a fixed
 * amount every time either is queried.
 */
public class IncrementingFakeClock implements Clock {
  private AtomicLong counter;
  private final long increment;


  public IncrementingFakeClock() {
    this(1);
  }

  public IncrementingFakeClock(long increment) {
    this.counter = new AtomicLong();
    this.increment = increment;
  }

  @Override
  public long currentTimeMillis() {
    // In our world, currentTimeMillis() is based on nanoTime().
    // This isn't the case for normal clocks, but it works for us.
    return TimeUnit.NANOSECONDS.toMillis(nanoTime());
  }

  @Override
  public long nanoTime() {
    return counter.addAndGet(increment);
  }
}
