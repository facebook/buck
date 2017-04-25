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

/** Provides a fake implementation of a {@link Clock} which always returns a constant time. */
public class FakeClock implements Clock {
  private final long nanoTime;

  public FakeClock(long nanoTime) {
    this.nanoTime = nanoTime;
  }

  @Override
  public long currentTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(nanoTime);
  }

  @Override
  public long nanoTime() {
    return nanoTime;
  }

  @Override
  public long threadUserNanoTime(long threadId) {
    return -1;
  }
}
