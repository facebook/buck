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

package com.facebook.buck.intellij.ideabuck.config;

import com.facebook.buck.intellij.ideabuck.util.ExecutableFinder;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BuckExecutableDetector {
  private static final Path BUCK_EXECUTABLE = Paths.get("buck");
  private static final Path ADB_EXECUTABLE = Paths.get("adb");
  private static final ExecutableFinder EXECUTABLE_FINDER = new ExecutableFinder();

  private BuckExecutableDetector() {}

  public static String getBuckExecutable() {
    return getExecutable(BUCK_EXECUTABLE);
  }

  public static String getAdbExecutable() {
    return getExecutable(ADB_EXECUTABLE);
  }

  public static String getExecutable(Path suggestedExecutable) {
    return EXECUTABLE_FINDER
        .getExecutable(suggestedExecutable, ImmutableMap.copyOf(System.getenv()))
        .toString();
  }
}
