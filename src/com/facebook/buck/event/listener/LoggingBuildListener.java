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

package com.facebook.buck.event.listener;

import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.StepEvent;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;

/** Log handler to bridge BuckEventBus events to java.util.logging records. */
public class LoggingBuildListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(LoggingBuildListener.class);

  /**
   * These are message types that have custom handling or where we want to do nothing because they
   * are logged inline before being posted.
   */
  private static final ImmutableSet<Class<? extends AbstractBuckEvent>>
      EXPLICITLY_HANDLED_EVENT_TYPES =
          ImmutableSet.<Class<? extends AbstractBuckEvent>>builder()
              .add(ConsoleEvent.class)
              .add(BuildEvent.Started.class)
              .add(BuildEvent.Finished.class)
              .add(BuildRuleEvent.Started.class)
              .add(BuildRuleEvent.Finished.class)
              .add(BuildRuleEvent.Suspended.class)
              .add(BuildRuleEvent.Resumed.class)
              .add(StepEvent.Started.class)
              .add(StepEvent.Finished.class)
              .build();

  private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"));

  @Subscribe
  public void handleConsoleEvent(ConsoleEvent logEvent) {
    // Since SEVERE events interrupt the super console, we reserve them
    // only for exceptional events, and so cap log messages at WARNING here.
    // Long-term, we likely should have a separate leveling mechanism for
    // Buck's `ConsoleEvent`.
    Consumer<String> logFn = LOG::warn;
    if (logEvent.getLevel().intValue() <= Level.FINER.intValue()) {
      logFn = LOG::verbose;
    } else if (logEvent.getLevel().intValue() <= Level.FINE.intValue()) {
      logFn = LOG::debug;
    } else if (logEvent.getLevel().intValue() <= Level.INFO.intValue()) {
      logFn = LOG::info;
    }
    logFn.accept(logEvent.getMessage());
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    LOG.info("Build started at %s", formatTimestamp(started.getTimestamp()));
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    LOG.info("Build finished at %s", formatTimestamp(finished.getTimestamp()));
  }

  @Subscribe
  public void handleFallback(Object event) {
    // This receives a lot of events. Exit fast if verbose logging is not enabled.
    if (!LOG.isVerboseEnabled()) {
      return;
    }
    if (EXPLICITLY_HANDLED_EVENT_TYPES.contains(event.getClass())) {
      return;
    }
    LOG.verbose("%s", event);
  }

  @Override
  public void outputTrace(BuildId buildId) {
    for (Handler h : LOG.getHandlers()) {
      h.flush();
    }
  }

  private String formatTimestamp(long millis) {
    return DATE_FORMAT.get().format(new Date(millis));
  }
}
