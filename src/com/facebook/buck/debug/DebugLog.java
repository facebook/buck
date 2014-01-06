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

package com.facebook.buck.debug;

import com.google.common.base.Throwables;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.annotation.concurrent.GuardedBy;

/**
 * Low-level log that allows us to quickly debug issues occurring our build servers.
 * Logging calls can be made from everywhere, but should be deleted promptly.
 * Long-term logging code needs a more configurable solution.
 */
public class DebugLog {
  /**
   * Singleton instance.
   */
  @GuardedBy("DebugLog.class")
  private static DebugLog instance;

  @GuardedBy("this")
  private final Writer output;

  private DebugLog() throws IOException {
    Files.createDirectories(Paths.get("./buck-out/log"));
    output = new BufferedWriter(new FileWriter("./buck-out/log/debug.log", true));
  }

  private static synchronized DebugLog get() {
    try {
      if (instance == null) {
        instance = new DebugLog();
      }
      return instance;
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private synchronized void doLog(String message) {
    try {
      output.write(message);
      output.write("\n");
      output.flush();
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static void log(String message) {
    get().doLog(message);
  }
}
