/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.concurrent.MostExecutors.NamedThreadFactory;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.eventbus.Subscribe;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultBuckEventBusTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final int TIMEOUT_MILLIS = 500;
  private static final Instant AT_TIME = Instant.parse("2020-04-24T12:13:14.123456789Z");

  private final DefaultBuckEventBus buckEventBus =
      new DefaultBuckEventBus(
          new DefaultClock(), false, BuckEventBusForTests.BUILD_ID_FOR_TEST, TIMEOUT_MILLIS);

  @Test
  public void testShutdownSuccess() {
    buckEventBus.register(new SleepSubscriber());
    buckEventBus.post(new SleepEvent(1));
    long start = System.nanoTime();
    buckEventBus.close();
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat(
        "Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo((long) TIMEOUT_MILLIS));
  }

  @Test
  public void testShutdownFailure() {
    buckEventBus.register(new SleepSubscriber());
    buckEventBus.post(new SleepEvent(TIMEOUT_MILLIS * 3));
    long start = System.nanoTime();
    buckEventBus.close();
    // We'd like to test the Logger output here, but there's not a clean way to do that.
    long durationNanos = System.nanoTime() - start;
    long durationMillis = TimeUnit.MILLISECONDS.convert(durationNanos, TimeUnit.NANOSECONDS);
    assertThat(
        "Shutdown should not take a long time.",
        durationMillis,
        lessThanOrEqualTo((long) TIMEOUT_MILLIS * 2));
  }

  @Test
  public void whenEventTimestampedThenEventCannotBePosted() {
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(equalTo("Events can only be configured once."));

    TestEvent event = new TestEvent();
    buckEventBus.timestamp(event);
    try {
      buckEventBus.post(event);
    } finally {
      buckEventBus.close();
    }
  }

  @Test
  public void whenEventPostedWithTimestamp() {
    TestEvent event = new TestEvent();
    buckEventBus.post(event, AT_TIME);
    buckEventBus.close();

    verifyTimestampMillis(event);
    assertEquals(Thread.currentThread().getId(), event.getThreadId());
  }

  @Test
  public void whenEventPostedWithTimestampAndThreadId() {
    TestEvent event = new TestEvent();
    int threadId = 123;
    buckEventBus.post(event, AT_TIME, threadId);
    buckEventBus.close();

    verifyTimestampMillis(event);
    assertEquals(threadId, event.getThreadId());
  }

  @Test
  public void whenEventPostedWithThreadId() {
    TestEvent event = new TestEvent();
    int threadId = 123;
    buckEventBus.post(event, threadId);
    buckEventBus.close();

    assertEquals(threadId, event.getThreadId());
  }

  private void verifyTimestampMillis(TestEvent event) {
    long epochSecond = AT_TIME.getEpochSecond();
    int nano = AT_TIME.getNano();

    assertEquals(
        TimeUnit.SECONDS.toMillis(epochSecond) + TimeUnit.NANOSECONDS.toMillis(nano),
        event.getTimestampMillis());
  }

  @Test
  public void timestampedEventHasSeparateNanosAndMillis() {
    SettableFakeClock fakeClock = new SettableFakeClock(49152, 64738);
    DefaultBuckEventBus buckEventBus =
        new DefaultBuckEventBus(
            fakeClock, false, BuckEventBusForTests.BUILD_ID_FOR_TEST, TIMEOUT_MILLIS);
    TestEvent event = new TestEvent();
    buckEventBus.post(event);
    buckEventBus.close();
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
            TIMEOUT_MILLIS,
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
