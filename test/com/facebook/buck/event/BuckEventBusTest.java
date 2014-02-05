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

package com.facebook.buck.event;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ShutdownException;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class BuckEventBusTest {

  private static final int timeoutMillis = 500;

  @Test
  public void testShutdownSuccess() throws Exception {
    BuckEventBus eb = new BuckEventBus(
        new DefaultClock(),
        MoreExecutors.newSingleThreadExecutor(BuckEventBus.class.getSimpleName()),
        BuckEventBusFactory.BUILD_ID_FOR_TEST,
        timeoutMillis);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(1));
    long start = System.nanoTime();
    try {
      eb.close();
    } catch (ShutdownException e) {
      fail("Bus should shut down successfully.");
    }
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat("Shutdown should not take a long time.",
        durationMillis, lessThanOrEqualTo((long) timeoutMillis));
  }

  @Test
  public void testShutdownFailure() throws Exception {
    BuckEventBus eb = new BuckEventBus(
        new DefaultClock(),
        MoreExecutors.newSingleThreadExecutor(BuckEventBus.class.getSimpleName()),
        BuckEventBusFactory.BUILD_ID_FOR_TEST,
        timeoutMillis);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(timeoutMillis * 3));
    long start = System.nanoTime();
    try {
      eb.close();
      fail("Bus should not shut down successfully.");
    } catch (ShutdownException e) {
      assertThat("Exception should be due to shutdown.",
          e.getMessage(),
          Matchers.containsString("failed to shut down"));
    }
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat("Shutdown should not take a long time.",
        durationMillis, lessThanOrEqualTo((long) timeoutMillis * 2));
  }

  private static class SleepEvent extends AbstractBuckEvent {
    public final long milliseconds;

    private SleepEvent(long milliseconds) {
      this.milliseconds = milliseconds;
    }

    @Override
    protected String getValueString() {
      return null;
    }

    @Override
    public boolean eventsArePair(BuckEvent event) {
      return false;
    }

    @Override
    public String getEventName() {
      return null;
    }
  }

  private static class SleepSubscriber {
    @Subscribe
    public void sleep(SleepEvent event) {
      try {
        Thread.sleep(event.milliseconds);
      } catch (InterruptedException e) {
        throw Throwables.propagate(e);
      }
    }
  }

}
