/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.step.Verbosity;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Logging {

  private Logging() {}

  public static void setLoggingLevelForVerbosity(Verbosity verbosity) {
    Level loggerLevel = verbosity == Verbosity.ALL
        ? Level.ALL
        : (verbosity.shouldPrintOutput()) ? Level.INFO : Level.OFF;
    // TODO(mbolin): Figure out why "com.facebook.buck" cannot be used instead of "".
    Logger.getLogger("").setLevel(loggerLevel);
  }

}
