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

package com.facebook.buck.util.timing;

import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

/** Provides a fake implementation of a {@link Clock} which always returns a constant time. */
@Value.Immutable
@Value.Style(typeImmutable = "*")
public abstract class AbstractFakeClock implements Clock {
  /** FakeClock instance for tests that don't require specific timestamp values. */
  public static FakeClock doNotCare() {
    return FakeClock.builder()
        .currentTimeMillis(1337)
        .nanoTime(TimeUnit.MILLISECONDS.toNanos(4242))
        .build();
  }

  @Override
  public abstract long currentTimeMillis();

  @Override
  public abstract long nanoTime();

  @Value.Check
  protected void checkNanoTimeIsNotDerivedFromCurrentTimeMillis() {
    // Being a little overly conservative here given the method name, but really nano time should
    // never be anywhere near currentTimeMillis so it's OK.
    Preconditions.checkState(
        Math.abs(TimeUnit.NANOSECONDS.toMillis(nanoTime()) - currentTimeMillis()) > 1);
  }

  @Override
  public long threadUserNanoTime(long threadId) {
    return -1;
  }
}
