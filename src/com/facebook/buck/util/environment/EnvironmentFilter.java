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

package com.facebook.buck.util.environment;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Locale;
import java.util.Map;

/**
 * Utility class to filter system environment maps.
 */
public class EnvironmentFilter {

  // Always exclude environment variables with these names.
  private static final ImmutableSet<String> ENV_TO_REMOVE = ImmutableSet.of(
      "Apple_PubSub_Socket_Render", // OS X pubsub control variable.
      "ANDROID_SERIAL",   // Serial of the target Android device/emulator.
      "BUCK_BUILD_ID",    // Build ID passed in from Python.
      "BUCK_CLASSPATH",   // Main classpath; set in Python
      "CLASSPATH",        // Bootstrap classpath; set in Python.
      "CMD_DURATION",      // Added to environment by 'fish' shell.
      "COMP_WORDBREAKS",  // Set by the programmable completion part of bash.
      "ITERM_SESSION_ID", // Added by iTerm on OS X.
      "ITERM_PROFILE",    // Added by iTerm on OS X.
      "KRB5CCNAME",       // Kerberos authentication adds this.
      "OLDPWD",           // Previous working directory; set by bash's cd builtin.
      "PROMPT_COMMAND",   // Prompt control variable, just in case someone exports it.
      "PS1",              // Same.
      "PS2",              // Same.
      "PS3",              // Same.
      "PS4",              // Same.
      "PWD",              // Current working directory; set by bash.
      "SHLVL",            // Shell nestedness level; set by bash.
      "SSH_AGENT_PID",    // SSH session management variable.
      "SSH_AUTH_SOCK",    // Same.
      "SSH_CLIENT",       // Same.
      "SSH_CONNECTION",   // Same.
      "SSH_TTY",          // Same.
      "TMUX",             // tmux session management variable.
      "TMUX_PANE",        // Current tmux pane.
      "TERM_SESSION_ID"  // UUID added to environment by OS X.
  );

  // Ignore the environment variables with these names when comparing environments.
  private static final ImmutableSet<String> ENV_TO_IGNORE = ImmutableSet.of(
      "NAILGUN_TTY_1",  // Nailgun stdout supports ANSI escape sequences.
      "NAILGUN_TTY_2"   // Nailgun stderr supports ANSI escape sequences.
  );

  public static final Predicate<String> NOT_IGNORED_ENV_PREDICATE =
      Predicates.not(Predicates.in(ENV_TO_IGNORE));

  // Utility class, do not instantiate.
  private EnvironmentFilter() { }

  /**
   * Given a map (environment variable name: environment variable
   * value) pairs, returns a map without the variables which we should
   * not pass to child processes (buck.py, javac, etc.)
   *
   * Keeping the environment map clean helps us avoid jettisoning the
   * parser cache, as we have to rebuild it any time the environment
   * changes.
   */
  public static ImmutableMap<String, String> filteredEnvironment(
      ImmutableMap<String, String> environment, Platform platform) {
    ImmutableMap.Builder<String, String> filteredEnvironmentBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> envEntry : environment.entrySet()) {
      String key = envEntry.getKey();
      if (!ENV_TO_REMOVE.contains(key)) {
        if (platform == Platform.WINDOWS) {
          // Windows environment variables are case insensitive.  While an ImmutableMap will throw
          // if we get duplicate key, we don't have to worry about this for Windows.
          filteredEnvironmentBuilder.put(key.toUpperCase(Locale.US), envEntry.getValue());
        } else {
          filteredEnvironmentBuilder.put(envEntry);
        }
      }
    }
    return filteredEnvironmentBuilder.build();
  }
}
