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

package com.facebook.buck.event.utils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.protobuf.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class EventBusUtilsTest {

  @Test
  public void millisToDurationWhereNumberOfMillisRepresentSecond() {
    int seconds = 123;
    Duration duration = EventBusUtils.millisToDuration(TimeUnit.SECONDS.toMillis(seconds));
    assertThat(duration.getSeconds(), equalTo((long) seconds));
    assertThat(duration.getNanos(), equalTo(0));
  }

  @Test
  public void millisToDurationWhereNumberOfMillisRepresentSecondWithRemainder() {
    int seconds = 123;
    int extraMillis = 789;
    int totalMillis = (int) (TimeUnit.SECONDS.toMillis(seconds) + extraMillis);

    Duration duration = EventBusUtils.millisToDuration(totalMillis);
    assertThat(duration.getSeconds(), equalTo((long) seconds));
    assertThat(duration.getNanos(), equalTo((int) TimeUnit.MILLISECONDS.toNanos(extraMillis)));
  }

  @Test
  public void configureEvent() {
    int threadId = 42;
    long millis = 123456789_123465789L;
    long nanos = 123456789_987654321L;
    BuckEvent event = new FakeBuckEvent();
    Instant atTime = Instant.ofEpochMilli(millis).plusNanos(-123_123_123);
    EventBusUtils.configureEvent(
        event, atTime, threadId, new SettableFakeClock(millis, nanos), new BuildId("fake"));

    assertThat(event.getTimestampMillis(), equalTo(atTime.toEpochMilli()));

    long eventNanos =
        nanos
            - TimeUnit.MILLISECONDS.toNanos(atTime.toEpochMilli())
            + TimeUnit.SECONDS.toNanos(atTime.getEpochSecond())
            + atTime.getNano();
    assertThat(event.getNanoTime(), equalTo(eventNanos));
  }

  private static class FakeBuckEvent extends AbstractBuckEvent {

    protected FakeBuckEvent() {
      super(EventKey.of(42));
    }

    @Override
    public String getEventName() {
      return "fake";
    }

    @Override
    protected String getValueString() {
      return "fake";
    }
  }
}
