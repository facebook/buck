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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

public class DistBuildUtil {
  private static final Logger LOG = Logger.get(ConsoleEvent.class);
  private static final DateFormat DATE_FORMAT = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]");

  private DistBuildUtil() {}

  public static BuildSlaveConsoleEvent createBuildSlaveConsoleEvent(
      ConsoleEvent event, long timestampMillis) {
    BuildSlaveConsoleEvent buildSlaveConsoleEvent = new BuildSlaveConsoleEvent();
    buildSlaveConsoleEvent.setTimestampMillis(timestampMillis);
    buildSlaveConsoleEvent.setMessage(event.getMessage());

    if (event.getLevel().equals(Level.WARNING)) {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.WARNING);
    } else if (event.getLevel().equals(Level.SEVERE)) {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.SEVERE);
    } else {
      buildSlaveConsoleEvent.setSeverity(ConsoleEventSeverity.INFO);
    }

    return buildSlaveConsoleEvent;
  }

  public static ConsoleEvent createConsoleEvent(BuildSlaveConsoleEvent event) {
    Preconditions.checkState(event.isSetMessage());
    Preconditions.checkState(event.isSetSeverity());
    Preconditions.checkState(event.isSetTimestampMillis());

    String timestampPrefix = DATE_FORMAT.format(new Date(event.getTimestampMillis())) + " ";
    switch (event.getSeverity()) {
      case INFO:
        return ConsoleEvent.create(Level.INFO, timestampPrefix + event.getMessage());
      case WARNING:
        return ConsoleEvent.create(Level.WARNING, timestampPrefix + event.getMessage());
      case SEVERE:
        return ConsoleEvent.create(Level.SEVERE, timestampPrefix + event.getMessage());
      default:
        LOG.error(
            String.format(
                "Unsupported type of ConsoleEventSeverity received in BuildSlaveConsoleEvent: [%d]"
                    + "Defaulting to SEVERE.",
                event.getSeverity().getValue()));
        return ConsoleEvent.create(Level.SEVERE, timestampPrefix + event.getMessage());
    }
  }
}
