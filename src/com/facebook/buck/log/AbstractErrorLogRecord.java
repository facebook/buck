/*
 * Copyright 2016-present Facebook, Inc.
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

import static com.facebook.buck.util.MoreThrowables.getInitialCause;
import static com.facebook.buck.util.MoreThrowables.getThrowableOrigin;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

import java.util.logging.LogRecord;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractErrorLogRecord {

  private static final ThreadIdToCommandIdMapper MAPPER = GlobalStateManager
      .singleton()
      .getThreadIdToCommandIdMapper();

  public abstract LogRecord getRecord();
  public abstract String getMessage();
  public abstract ImmutableList<String> getLogs();

  @Value.Derived
  public ImmutableMap<String, String> getTraits() {
    String logger = getRecord().getLoggerName();
    ImmutableMap<String, String> traits = ImmutableMap.of(
        "severity", getRecord().getLevel().toString(),
        "logger", logger != null ? logger : "None"
    );
    return traits;
  }

  /**
   * Computes a category key based on relevant LogRecord information. If an exception is
   * present, categorizes on the class + method that threw it. If no exception
   * is found, categorizes on the logger name and the beginning of the message.
   */
  @Value.Derived
  public String getCategory() {
    String logger = "";
    if (getRecord().getLoggerName() != null) {
      logger = getRecord().getLoggerName();
    }
    StringBuilder sb = new StringBuilder(logger).append(":");
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      sb.append(extractClassMethod(getThrowableOrigin(getInitialCause(throwable))));
    } else {
      sb.append(truncateMessage(getRecord().getMessage()));
    }
    return sb.toString();
  }

  @Value.Derived
  public long getTime() {
    return getRecord().getMillis();
  };

  @Value.Derived
  public String getLogger() {
    return getRecord().getLoggerName();
  };

  @Value.Derived
  public String getCommandId() {
    return MAPPER.threadIdToCommandId(getRecord().getThreadID());
  }

  @Value.Derived
  public Optional<StackTraceElement[]> getStack() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.of(throwable.getStackTrace());
    }
    return Optional.absent();
  }

  @Value.Derived
  public Optional<String> getInitialError() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.of(getInitialCause(throwable).getClass().getName());
    }
    return Optional.absent();
  }

  @Value.Derived
  public Optional<String> getInitialErrorMsg() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.of(getInitialCause(throwable).getLocalizedMessage());
    }
    return Optional.absent();
  }

  @Value.Derived
  public Optional<String> getOrigin() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.of(getThrowableOrigin(throwable));
    }
    return Optional.absent();
  }

  /**
   * We expect uploaded log records to contain a stack trace, but if they don't
   * the logged message is important. To address the issue that these records
   * often contain parametrized values, only first word (1 & 2 if first has 2 or
   * less chars) of message is taken into account.
   */
  private String truncateMessage(String name) {
    String[] words = name.split("\\s+");
    if (words.length > 1 && words[0].length() < 3) {
      return words[0] + " " + words[1];
    }
    return words[0];
  }

  /**
   * Extracts minimum valuable information set from lines in the following format:
   * package.classname.method(filename:line_number)
   */
  private String extractClassMethod(String name) {
    if (name != null) {
      return name.split("\\(", 1)[0];
    }
    return "";
  }
}
