/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.downwardapi.utils;

import com.facebook.buck.downward.model.LogLevel;
import java.util.logging.Level;

/** Utility methods related to downward API. */
public class DownwardApiUtils {

  private DownwardApiUtils() {}

  /**
   * Returns the {@link LogLevel} associated with the given {@link Level}. Based on values from
   * //xplat/build_infra/buck_client/config/logging.properties.st
   */
  public static LogLevel getLogLevel(Level level) {
    if (level.equals(Level.SEVERE)) {
      return LogLevel.ERROR;
    }
    if (level.equals(Level.WARNING)) {
      return LogLevel.WARN;
    }
    if (level.equals(Level.INFO)) {
      return LogLevel.INFO;
    }
    if (level.equals(Level.FINE)) {
      return LogLevel.DEBUG;
    }
    if (level.equals(Level.FINER)) {
      return LogLevel.TRACE;
    }
    // LEVEL.CONFIG, LEVEL.FINEST, LEVEL.DEBUG, LEVEL.ALL, LEVEL.OFF would all map to
    // LogLevel.UNKNOWN
    return LogLevel.UNKNOWN;
  }
}
