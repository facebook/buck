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
package com.facebook.buck.event;

import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.eventbus.EventBus;

/**
 * Thin wrapper around guava event bus.
 */
public class BuckEventBus {
  private static Supplier<Long> DEFAULT_THREAD_ID_SUPPLIER = new Supplier<Long>() {
    @Override
    public Long get() {
      return Thread.currentThread().getId();
    }
  };

  public static Supplier<Long> getDefaultThreadIdSupplier() {
    return DEFAULT_THREAD_ID_SUPPLIER;
  }

  private final EventBus eventBus;
  private final Clock clock;
  private final Supplier<Long> threadIdSupplier;

  public BuckEventBus() {
    this(new EventBus(), new DefaultClock(), getDefaultThreadIdSupplier());
  }

  public BuckEventBus(EventBus eventBus, Clock clock, Supplier<Long> threadIdSupplier) {
    this.eventBus = Preconditions.checkNotNull(eventBus);
    this.clock = Preconditions.checkNotNull(clock);
    this.threadIdSupplier = Preconditions.checkNotNull(threadIdSupplier);
  }

  public void post(BuckEvent event) {
    event.configure(clock.currentTimeMillis(), clock.nanoTime(), threadIdSupplier.get());
    eventBus.post(event);
  }

  public void register(Object object) {
    eventBus.register(object);
  }

  public Clock getClock() {
    return clock;
  }

  public Supplier<Long> getThreadIdSupplier() {
    return threadIdSupplier;
  }
}
