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

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class LogFileHandler extends FileHandler {

  private final LogFileHandlerState state;

  public LogFileHandler() throws IOException, SecurityException {
    this(GlobalStateManager.singleton().getLogFileHandlerState(), new LogFormatter());
  }

  @VisibleForTesting
  LogFileHandler(LogFileHandlerState state,
      LogFormatter formatter) throws IOException, SecurityException {
    this.state = state;
    setFormatter(formatter);
  }

  @Override
  public synchronized void publish(LogRecord record) {
    String commandId = state.threadIdToCommandId(record.getThreadID());
    String formattedMsg = getFormatter().format(record);
    for (OutputStreamWriter writer : state.getWriters(commandId)) {
      try {
        writer.write(formattedMsg);
        if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
          writer.flush();
        }
      } catch (IOException e) { // NOPMD
        // There's a chance the writer may have been concurrently closed.
      }
    }
  }
}
