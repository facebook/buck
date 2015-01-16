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

package com.facebook.buck.cli;

import com.facebook.buck.util.environment.Platform;
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
      "BUCK_BUILD_ID",  // Build ID passed in from Python.
      "BUCK_CLASSPATH", // Main classpath; set in Python
      "CLASSPATH",      // Bootstrap classpath; set in Python.
      "TERM_SESSION_ID", // UUID added to environment by OS X.
      "CMD_DURATION"    // Added to environment by 'fish' shell.
  );

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
      ImmutableMap<String, String> environment) {
    ImmutableMap.Builder<String, String> filteredEnvironmentBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> envEntry : environment.entrySet()) {
      String key = envEntry.getKey();
      if (!ENV_TO_REMOVE.contains(key)) {
        if (Platform.detect() == Platform.WINDOWS) {
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
