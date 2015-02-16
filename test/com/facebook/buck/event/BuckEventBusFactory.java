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

import com.facebook.buck.model.BuildId;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.logging.Level;

/**
 * Factory to create a {@link BuckEventBus} for tests.
 * <p>
 * Also provides access to fields of a {@link BuckEventBus} that are not visible to the business
 * logic.
 */
public class BuckEventBusFactory {

  public static final BuildId BUILD_ID_FOR_TEST = new BuildId("CAFEBABE");

  /** Utility class: do not instantiate. */
  private BuckEventBusFactory() {}

  @VisibleForTesting
  public static BuckEventBus newInstance() {
    return newInstance(new DefaultClock(), BUILD_ID_FOR_TEST);
  }

  @VisibleForTesting
  public static BuckEventBus newInstance(Clock clock) {
    return newInstance(clock, BUILD_ID_FOR_TEST);
  }

  /**
   * This registers an {@link ErrorListener}. This is helpful when errors are logged during tests
   * that would not otherwise be noticed.
   */
  public static BuckEventBus newInstance(Clock clock, BuildId buildId) {
    BuckEventBus buckEventBus = new BuckEventBus(clock,
        MoreExecutors.newDirectExecutorService(),
        buildId,
        BuckEventBus.DEFAULT_SHUTDOWN_TIMEOUT_MS);
    buckEventBus.register(new ErrorListener());
    return buckEventBus;
  }

  public static EventBus getEventBusFor(BuckEventBus buckEventBus) {
    return buckEventBus.getEventBus();
  }

  /**
   * Error listener that prints events at level {@link Level#WARNING} or higher.
   */
  private static class ErrorListener {
    @Subscribe
    public void logEvent(ConsoleEvent event) {
      Level level = event.getLevel();
      if (level.intValue() >= Level.WARNING.intValue()) {
        System.err.println(event.getMessage());
      }
    }
  }

  public static class CapturingConsoleEventListener {
    private final List<ConsoleEvent> logEvents = Lists.newArrayList();

    @Subscribe
    public void logEvent(ConsoleEvent event) {
      logEvents.add(event);
    }

    public List<String> getLogMessages() {
      return FluentIterable.from(logEvents).transform(Functions.toStringFunction()).toList();
    }
  }
}
