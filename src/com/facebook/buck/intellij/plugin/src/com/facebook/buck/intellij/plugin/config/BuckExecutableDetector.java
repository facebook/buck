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

package com.facebook.buck.intellij.plugin.config;

import com.intellij.openapi.util.SystemInfo;

import java.io.File;

public final class BuckExecutableDetector {

  private BuckExecutableDetector() {
  }

  private static final String[] UNIX_PATHS = {
      "/usr/local/bin",
      "/usr/bin",
      "/opt/local/bin",
      "/opt/bin",
  };
  private static final String UNIX_EXECUTABLE = "buck";

  public static String detect() {
    if (SystemInfo.isWindows) {
      return null;
    }
    return detectForUnix();
  }

  private static String detectForUnix() {
    // TODO(user): Use Buck's ExecutableFinder class here.
    for (String p : UNIX_PATHS) {
      File f = new File(p, UNIX_EXECUTABLE);
      if (f.exists()) {
        return f.getPath();
      }
    }
    return null;
  }
}
