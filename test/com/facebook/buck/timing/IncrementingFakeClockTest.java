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

package com.facebook.buck.timing;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class IncrementingFakeClockTest {
  @Test
  public void millisAddsIncrementAfterCall() {
    Clock clock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(64738));
    assertThat(clock.currentTimeMillis(), is(64738L));
  }

  @Test
  public void nanosAddsIncrementAfterCall() {
    Clock clock = new IncrementingFakeClock(49152);
    assertThat(clock.nanoTime(), is(49152L));
  }
}
