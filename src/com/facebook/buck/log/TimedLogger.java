/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.log;

import com.facebook.buck.core.util.log.Logger;
import com.google.common.base.Stopwatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Records the time that logging calls takes, and alerts if they go over configured threshold. */
public class TimedLogger {
  private static final int DEFAULT_MAX_LOG_CALL_DURATION_MILLIS = 5000;
  private final Logger delegate;
  private final int maxLogCallDurationMillis;
  private final AtomicInteger currentLogRequestId = new AtomicInteger(0);

  public TimedLogger(Logger delegate) {
    this(delegate, DEFAULT_MAX_LOG_CALL_DURATION_MILLIS);
  }

  public TimedLogger(Logger delegate, int maxLogCallDurationMillis) {
    this.delegate = delegate;
    this.maxLogCallDurationMillis = maxLogCallDurationMillis;
  }

  public void verbose(String rawMessage) {
    timedLogCalled(message -> delegate.verbose(message), rawMessage);
  }

  public void info(String rawMessage) {
    timedLogCalled(message -> delegate.info(message), rawMessage);
  }

  public void warn(String rawMessage) {
    timedLogCalled(message -> delegate.warn(message), rawMessage);
  }

  public void debug(String rawMessage) {
    timedLogCalled(message -> delegate.debug(message), rawMessage);
  }

  public void error(String rawMessage) {
    timedLogCalled(message -> delegate.error(message), rawMessage);
  }

  public void error(Throwable ex) {
    timedLogCalled(message -> delegate.error(ex, message), "");
  }

  public void error(Throwable ex, String rawMessage) {
    timedLogCalled(message -> delegate.error(ex, message), rawMessage);
  }

  public boolean isVerboseEnabled() {
    return delegate.isVerboseEnabled();
  }

  private void timedLogCalled(Consumer<String> logFunction, String rawLogMessage) {
    int logRequestId = this.currentLogRequestId.incrementAndGet();
    String logMessageWithId = String.format("%s [Log ID: %d]", rawLogMessage, logRequestId);

    Stopwatch stopwatch = Stopwatch.createStarted();
    logFunction.accept(logMessageWithId);
    long logCallDurationMillis = stopwatch.elapsed(TimeUnit.MILLISECONDS);

    if (logCallDurationMillis > maxLogCallDurationMillis) {
      delegate.warn(
          String.format(
              ("Log request with ID [%d] took [%d] milliseconds, "
                  + "which is longer than threshold of [%d] milliseconds."),
              logRequestId,
              logCallDurationMillis,
              maxLogCallDurationMillis));
    }
  }
}
