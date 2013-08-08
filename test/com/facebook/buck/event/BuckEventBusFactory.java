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
import com.google.common.base.Supplier;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Factory to create a {@link BuckEventBus} for tests.
 * <p>
 * Also provides access to fields of a {@link BuckEventBus} that are not visible to the business
 * logic.
 */
public class BuckEventBusFactory {

  /** Utility class: do not instantiate. */
  private BuckEventBusFactory() {}

  public static BuckEventBus newInstance() {
    return newInstance(new DefaultClock());
  }

  public static BuckEventBus newInstance(Clock clock) {
    return new BuckEventBus(clock, MoreExecutors.sameThreadExecutor());
  }

  public static EventBus getEventBusFor(BuckEventBus buckEventBus) {
    return buckEventBus.getEventBus();
  }

  public static Supplier<Long> getThreadIdSupplierFor(BuckEventBus buckEventBus) {
    return buckEventBus.getThreadIdSupplier();
  }
}
