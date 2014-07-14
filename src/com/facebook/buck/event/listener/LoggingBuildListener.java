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
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;

import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogRecord;

/**
 * Log handler to bridge BuckEventBus events to java.util.logging records.
 */
public class LoggingBuildListener implements BuckEventListener {

  private static final Logger LOG = Logger.getLogger(LoggingBuildListener.class.getName());
  private static final ImmutableSet<Class<? extends AbstractBuckEvent>>
      EXPLICITLY_HANDLED_EVENT_TYPES =
          ImmutableSet.of(
              ConsoleEvent.class,
              BuildEvent.Started.class,
              BuildEvent.Finished.class,
              BuildRuleEvent.Started.class,
              BuildRuleEvent.Finished.class);
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
    LogRecord record = new LogRecord(logEvent.getLevel(), logEvent.getMessage());
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
  public void ruleStarted(BuildRuleEvent.Started started) {
    LogRecord record = new LogRecord(
        Level.INFO,
        "Rule {0} started at {1}");
    record.setParameters(new Object[] { started, formatTimestamp(started.getTimestamp())});
    record.setLoggerName(getClass().getName());
    record.setMillis(started.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void ruleFinished(BuildRuleEvent.Finished finished) {
    LogRecord record = new LogRecord(
        Level.INFO,
        "Rule {0} finished at {1}");
    record.setParameters(new Object[] { finished, formatTimestamp(finished.getTimestamp()) });
    record.setLoggerName(getClass().getName());
    record.setMillis(finished.getTimestamp());
    LOG.log(record);
  }

  @Subscribe
  public void handleFallback(Object event) {
    if (EXPLICITLY_HANDLED_EVENT_TYPES.contains(event.getClass())) {
      return;
    }
    // Use a format so we avoid paying the cost of event.toString() unless we have to.
    LOG.log(Level.FINE, "{0}", event);
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
