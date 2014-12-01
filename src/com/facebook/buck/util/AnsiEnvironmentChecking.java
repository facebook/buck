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

package com.facebook.buck.util;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.environment.Platform;

import java.util.Map;
import java.util.Objects;

/**
 * Utility class to check if the environment supports ANSI escape sequences.
 */
public class AnsiEnvironmentChecking {
  // Utility class, do not instantiate.
  private AnsiEnvironmentChecking() { }

  private static final Logger LOG = Logger.get(AnsiEnvironmentChecking.class);
  private static final String TERM_ENV = "TERM";
  private static final String NAILGUN_STDOUT_ISTTY_ENV = "NAILGUN_TTY_1";
  private static final String NAILGUN_STDERR_ISTTY_ENV = "NAILGUN_TTY_2";

  /**
   * Returns true if the environment supports ANSI escape sequences, false otherwise.
   */
  public static boolean environmentSupportsAnsiEscapes(
      Platform platform,
      Map<String, String> environment) {
    boolean isWindows = (platform == Platform.WINDOWS);
    boolean isDumbTerminal = Objects.equals(environment.get(TERM_ENV), "dumb");

    String nailgunStdoutIsTtyEnv = environment.get(NAILGUN_STDOUT_ISTTY_ENV);
    String nailgunStderrIsTtyEnv = environment.get(NAILGUN_STDERR_ISTTY_ENV);
    boolean nailgunEnvPresent = (nailgunStdoutIsTtyEnv != null && nailgunStderrIsTtyEnv != null);
    boolean nailgunStdoutIsTty = Objects.equals(nailgunStdoutIsTtyEnv, "1");
    boolean nailgunStderrIsTty = Objects.equals(nailgunStderrIsTtyEnv, "1");

    boolean outputIsTty;
    if (nailgunEnvPresent) {
      outputIsTty = nailgunStdoutIsTty && nailgunStderrIsTty;
    } else {
      outputIsTty = System.console() != null;
    }

    boolean result = !isWindows && !isDumbTerminal && outputIsTty;

    LOG.verbose(
        "windows=%s dumbterm=%s nailgun=%s ng stdout=%s ng stderr=%s output tty=%s result=%s",
        isWindows,
        isDumbTerminal,
        nailgunEnvPresent,
        nailgunStdoutIsTty,
        nailgunStderrIsTty,
        outputIsTty,
        result);
    return result;
  }
}
