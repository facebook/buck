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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.MostExecutors.NamedThreadFactory;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DefaultBuckEventBusTest {

  private static final int timeoutMillis = 500;

  @Test
  public void testShutdownSuccess() throws Exception {
    DefaultBuckEventBus eb =
        new DefaultBuckEventBus(
            new DefaultClock(), false, BuckEventBusForTests.BUILD_ID_FOR_TEST, timeoutMillis);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(1));
    long start = System.nanoTime();
    eb.close();
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat(
        "Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo((long) timeoutMillis));
  }

  @Test
  public void testShutdownFailure() throws IOException {
    DefaultBuckEventBus eb =
        new DefaultBuckEventBus(
            new DefaultClock(), false, BuckEventBusForTests.BUILD_ID_FOR_TEST, timeoutMillis);
    eb.register(new SleepSubscriber());
    eb.post(new SleepEvent(timeoutMillis * 3));
    long start = System.nanoTime();
    eb.close();
    // We'd like to test the Logger output here, but there's not a clean way to do that.
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat(
        "Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo((long) timeoutMillis * 2));
  }

  @Test
  public void whenEventTimestampedThenEventCannotBePosted() throws IOException {
    DefaultBuckEventBus eb =
        new DefaultBuckEventBus(
            new DefaultClock(), false, BuckEventBusForTests.BUILD_ID_FOR_TEST, timeoutMillis);
    TestEvent event = new TestEvent();
    eb.timestamp(event);
    try {
      eb.post(event);
      fail("Post should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertThat(
          "Exception should be due to double configuration.",
          e.getMessage(),
          containsString("Events can only be configured once."));
    } finally {
      eb.close();
    }
  }

  @Test
  public void whenEventPostedWithAnotherThenTimestampCopiedToPostedEvent() throws IOException {
    DefaultBuckEventBus eb =
        new DefaultBuckEventBus(
            new DefaultClock(), false, BuckEventBusForTests.BUILD_ID_FOR_TEST, timeoutMillis);
    TestEvent timestamp = new TestEvent();
    TestEvent event = new TestEvent();
    eb.timestamp(timestamp);
    eb.post(event, timestamp);
    eb.close();
    assertEquals(timestamp.getTimestampMillis(), event.getTimestampMillis());
    assertEquals(timestamp.getNanoTime(), event.getNanoTime());
  }

  @Test
  public void timestampedEventHasSeparateNanosAndMillis() throws IOException {
    SettableFakeClock fakeClock = new SettableFakeClock(49152, 64738);
    DefaultBuckEventBus eb =
        new DefaultBuckEventBus(
            fakeClock, false, BuckEventBusForTests.BUILD_ID_FOR_TEST, timeoutMillis);
    TestEvent event = new TestEvent();
    eb.post(event);
    eb.close();
    assertEquals(event.getTimestampMillis(), 49152);
    assertEquals(event.getNanoTime(), 64738);
  }

  @Test
  public void testErrorThrownByListener() throws InterruptedException {
    SingleErrorCatchingThreadFactory threadFactory = new SingleErrorCatchingThreadFactory();
    DefaultBuckEventBus buckEventBus =
        new DefaultBuckEventBus(
            new DefaultClock(),
            BuckEventBusForTests.BUILD_ID_FOR_TEST,
            timeoutMillis,
            MostExecutors.newSingleThreadExecutor(threadFactory));

    buckEventBus.register(
        new Object() {
          @Subscribe
          public void errorThrower(TestEvent event) {
            throw new TestError();
          }
        });

    try {
      buckEventBus.post(new TestEvent());
    } finally {
      buckEventBus.close();
    }

    threadFactory.thread.join();
    assertTrue(threadFactory.caught);
  }

  static class TestError extends Error {}

  static class SingleErrorCatchingThreadFactory extends NamedThreadFactory {
    public Thread thread;
    public boolean caught = false;

    public SingleErrorCatchingThreadFactory() {
      super("test-thread");
    }

    @Override
    public Thread newThread(Runnable r) {
      thread = super.newThread(r);
      thread.setUncaughtExceptionHandler(
          (t, e) -> {
            if (e instanceof TestError) {
              caught = true;
            }
          });
      return thread;
    }
  }

  private static class SleepEvent extends AbstractBuckEvent {
    public final long milliseconds;

    private SleepEvent(long milliseconds) {
      super(EventKey.unique());
      this.milliseconds = milliseconds;
    }

    @Override
    protected String getValueString() {
      return null;
    }

    @Override
    public String getEventName() {
      return null;
    }
  }

  private static class SleepSubscriber {
    @Subscribe
    public void sleep(SleepEvent event) throws InterruptedException {
      Thread.sleep(event.milliseconds);
    }
  }

  private static class TestEvent extends AbstractBuckEvent {

    public TestEvent() {
      super(EventKey.unique());
    }

    @Override
    protected String getValueString() {
      return "Test event, please ignore.";
    }

    @Override
    public String getEventName() {
      return "TestEvent";
    }
  }
}
