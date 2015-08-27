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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.step.StepEvent;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Log handler to bridge BuckEventBus events to java.util.logging records.
 */
public class LoggingBuildListener implements BuckEventListener {

  private static final Logger LOG = Logger.getLogger(LoggingBuildListener.class.getName());

  /**
   * These are message types that have custom handling or where we want to do nothing
   * because they are logged inline before being posted.
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
    new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return format;
        }
    };

  @Subscribe
  public void handleConsoleEvent(ConsoleEvent logEvent) {
    LogRecord record =
        new LogRecord(
            // Since SEVERE events interrupt the super console, we reserve them
            // only for exceptional events, and so cap log messages at WARNING here.
            // Long-term, we likely should have a separate leveling mechanism for
            // Buck's `ConsoleEvent`.
            logEvent.getLevel().intValue() <= Level.WARNING.intValue() ?
                logEvent.getLevel() :
                Level.WARNING,
            logEvent.getMessage());
    record.setLoggerName(getClass().getName());
    LOG.log(record);
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    LogRecord record = new LogRecord(
        Level.INFO,
        "Build started at {0}");
    record.setParameters(new Object[] { formatTimestamp(started.getTimestamp()) });
    record.setLoggerName(getClass().getName());
    LOG.log(record);
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    LogRecord record = new LogRecord(
        Level.INFO,
        "Build finished at {0}");
    record.setParameters(new Object[] { formatTimestamp(finished.getTimestamp()) });
    record.setLoggerName(getClass().getName());
    LOG.log(record);
  }

  @Subscribe
  public void handleFallback(Object event) {
    if (EXPLICITLY_HANDLED_EVENT_TYPES.contains(event.getClass())) {
      return;
    }
    Level level = Level.FINE;
    if (event instanceof SimplePerfEvent) {
      level = Level.FINER;
    }
    // Use a format so we avoid paying the cost of event.toString() unless we have to.
    LOG.log(level, "{0}", event);
  }

  @Override
  public void outputTrace(BuildId buildId) {
    for (Handler h : Arrays.asList(LOG.getHandlers())) {
      h.flush();
    }
  }

  private String formatTimestamp(long millis) {
    return DATE_FORMAT.get().format(new Date(millis));
  }
}
