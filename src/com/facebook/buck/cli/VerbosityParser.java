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

import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;

class VerbosityParser {

  @VisibleForTesting static final String VERBOSE_LONG_ARG = "--verbose";

  @VisibleForTesting static final String VERBOSE_SHORT_ARG = "-v";

  @VisibleForTesting
  static final Verbosity DEFAULT_VERBOSITY = Verbosity.STANDARD_INFORMATION;

  private VerbosityParser() {}

  public static Verbosity parse(String... args) {
    for (int i = 0; i < args.length - 1; i++) {
      String arg = args[i];
      if (VERBOSE_LONG_ARG.equals(arg) || VERBOSE_SHORT_ARG.equals(arg)) {
        String nextArg = args[i + 1];
        int verbosityLevel = Integer.parseInt(nextArg, /* radix */ 10);
        return getVerbosityForLevel(verbosityLevel);
      }
    }
    return DEFAULT_VERBOSITY;
  }

  private static Verbosity getVerbosityForLevel(int verbosityLevel) {
    if (verbosityLevel >= 8) {
      return Verbosity.ALL;
    } else if (verbosityLevel >= 5) {
      return Verbosity.COMMANDS_AND_OUTPUT;
    } else if (verbosityLevel >= 2) {
      return Verbosity.COMMANDS;
    } else if (verbosityLevel >= 1) {
      return Verbosity.STANDARD_INFORMATION;
    } else {
      return Verbosity.SILENT;
    }
  }
}
