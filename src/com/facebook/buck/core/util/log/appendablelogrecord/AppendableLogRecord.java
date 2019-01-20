/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.core.util.log.appendablelogrecord;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class AppendableLogRecord extends LogRecord {
  private final String displayLevel;

  public AppendableLogRecord(Level level, String displayLevel, String msg) {
    super(level, msg);
    this.displayLevel = displayLevel;
  }

  public void appendFormattedMessage(StringBuilder sb) {
    // Unfortunately, there's no public API to reset a Formatter's
    // Appendable. If this proves to be a perf issue, we can do
    // runtime introspection to access the private Formatter.init()
    // API to replace the Appendable.

    try (Formatter f = new Formatter(sb, Locale.US)) {
      f.format(getMessage(), getParameters());
    } catch (IllegalFormatException e) {
      sb.append("Invalid format string: ");
      sb.append(displayLevel);
      sb.append(" '");
      sb.append(getMessage());
      sb.append("' ");
      Object[] params = getParameters();
      if (params == null) {
        params = new Object[0];
      }
      sb.append(Arrays.asList(params));
    } catch (ConcurrentModificationException originalException) {
      // This way we may be at least able to figure out where offending log was created.
      throw new ConcurrentModificationException(
          "Concurrent modification when logging for message " + getMessage(), originalException);
    }
  }
}
