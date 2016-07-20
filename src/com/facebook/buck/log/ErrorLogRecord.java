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
import static com.google.common.base.Throwables.getStackTraceAsString;

import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.logging.LogRecord;

public class ErrorLogRecord {

  private final ImmutableMap<String, String> traits;
  private final String category;
  private final String text;

  private static final ObjectMapper OBJECT_MAPPER = ObjectMappers.newDefaultInstance();
  private static final ThreadIdToCommandIdMapper COMMAND_ID_MAP = GlobalStateManager
      .singleton()
      .getThreadIdToCommandIdMapper();

  public ErrorLogRecord(
      LogRecord record,
      String message,
      ImmutableList<String> contextLogs) {
    this.traits = getTraits(record);
    this.category = getCategory(record);
    this.text = getText(record, message, contextLogs);
  }

  private ImmutableMap<String, String> getTraits(LogRecord record) {

    String logger = record.getLoggerName();
    ImmutableMap<String, String> traits = ImmutableMap.of(
        "severity", record.getLevel().toString(),

        "logger", logger != null ? logger : "None"
    );
    return traits;
  }

  private String getText(
      LogRecord record,
      String message,
      ImmutableList<String> contextLogs) {
    LogRecordFields.Builder logRecordFields = LogRecordFields.builder()
        .setMessage(message)
        .setLogs(contextLogs)
        .setLogger(record.getLoggerName())
        .setCommandId(COMMAND_ID_MAP.threadIdToCommandId(record.getThreadID()));
    Throwable throwable = record.getThrown();
    if (throwable != null) {
      Throwable initialCause = getInitialCause(throwable);
      logRecordFields.setCause(Optional.of(initialCause))
          .setInitialError(Optional.of(initialCause.getClass().getName()))
          .setInitialErrorMsg(Optional.of(initialCause.getLocalizedMessage()))
          .setOrigin(Optional.of(getThrowableOrigin(initialCause)))
          .setStackTrace(Optional.of(getStackTraceAsString(throwable)));
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(logRecordFields.build());
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return "";
  }

  /**
   * Computes a category key based on relevant LogRecord information. If an exception is
   * present, categorizes on the class + method that threw it. If no exception
   * is found, categorizes on the logger name and the beginning of the message.
   */
  private String getCategory(LogRecord record) {
    String logger = "";
    if (record.getLoggerName() != null) {
      logger = record.getLoggerName();
    }
    StringBuilder sb = new StringBuilder(logger).append(":");
    Throwable throwable = record.getThrown();
    if (throwable != null) {
      sb.append(extractClassMethod(getThrowableOrigin(getInitialCause(throwable))));
    } else {
      sb.append(truncateMessage(record.getMessage()));
    }
    return sb.toString();
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

  public ImmutableMap<String, String> getTraits() {
    return traits;
  }

  public String getCategory() {
    return category;
  }

  public String getText() {
    return text;
  }
}
