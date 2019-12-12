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

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class TestEventConfigurator {
  private TestEventConfigurator() {}

  public static <T extends AbstractBuckEvent> ConfigBuilder<T> from(T event) {
    return new ConfigBuilder<>(event);
  }

  public static <T extends AbstractBuckEvent> T configureTestEvent(T event) {
    return from(event).configure();
  }

  public static <T extends AbstractBuckEvent> T configureTestEventAtTime(
      T event, long time, TimeUnit timeUnit, long threadid) {
    return from(event)
        .setCurrentTimeMillis(timeUnit.toMillis(time))
        .setTimestampNanos(timeUnit.toNanos(time))
        .setThreadId(threadid)
        .configure();
  }

  public static class ConfigBuilder<T extends AbstractBuckEvent> {
    private final T event;
    private long currentTimeMillis;
    private Optional<Long> timestampNanos;
    private long threadUserNanoTime;
    private long threadId;

    public ConfigBuilder(T event) {
      this.event = event;
      this.currentTimeMillis = 0L;
      this.timestampNanos = Optional.empty();
      this.threadUserNanoTime = -1L;
      this.threadId = 2L;
    }

    public ConfigBuilder<T> setCurrentTime(long time, TimeUnit unit) {
      return setCurrentTimeMillis(unit.toMillis(time));
    }

    public ConfigBuilder<T> setCurrentTimeMillis(long timestamp) {
      this.currentTimeMillis = timestamp;
      return this;
    }

    public ConfigBuilder<T> setTimestampNanos(long nanoTime) {
      this.timestampNanos = Optional.of(nanoTime);
      return this;
    }

    public ConfigBuilder<T> setThreadUserTime(long time, TimeUnit timeUnit) {
      return setThreadUserNanoTime(timeUnit.toNanos(time));
    }

    public ConfigBuilder<T> setThreadUserNanoTime(long threadUserNanoTime) {
      this.threadUserNanoTime = threadUserNanoTime;
      return this;
    }

    public ConfigBuilder<T> setThreadId(long threadId) {
      this.threadId = threadId;
      return this;
    }

    public T configure() {
      event.configure(
          currentTimeMillis,
          timestampNanos.orElse(TimeUnit.MILLISECONDS.toNanos(currentTimeMillis)),
          threadUserNanoTime,
          threadId,
          BuckEventBusForTests.BUILD_ID_FOR_TEST);
      return event;
    }
  }
}
