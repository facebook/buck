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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.timing.DefaultClock;
import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class BuckEventBusTest {
  @Test
  public void testShutdownSuccess() throws Exception {
    BuckEventBus eb = new BuckEventBus(new DefaultClock(), BuckEventBusFactory.BUILD_ID_FOR_TEST);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(1));
    long start = System.nanoTime();
    boolean success = eb.shutdown(2500, TimeUnit.MILLISECONDS);
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertTrue("Bus should shut down successfully", success);
    assertThat("Shutdown should not take a long time.",
        durationMillis, lessThanOrEqualTo(2400L));
  }

  @Test
  public void testShutdownFailure() throws Exception {
    BuckEventBus eb = new BuckEventBus(new DefaultClock(), BuckEventBusFactory.BUILD_ID_FOR_TEST);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(2500));
    long start = System.nanoTime();
    boolean success = eb.shutdown(500, TimeUnit.MILLISECONDS);
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertFalse("Bus should not shut down successfully", success);
    assertThat("Shutdown should not take a long time.",
        durationMillis, lessThanOrEqualTo(2400L));
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
