/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.sqlite;

import com.facebook.buck.util.HumanReadableException;
import org.sqlite.SQLiteJDBCLoader;

public class SQLiteUtils {
  private SQLiteUtils() {
    // This class cannot be instantiated
  }

  /**
   * Initializes the JDBC loader statically to avoid sqlite-jdbc loading its JNI library in a
   * thread-unsafe manner. This method should be called statically from any class that uses
   * SQLiteJDBC.
   */
  public static synchronized void initialize() {
    boolean success;
    try {
      success = SQLiteJDBCLoader.initialize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    if (!success) {
      throw new HumanReadableException(
          "Failed to initialize Buck (sqlite-jdbc). A common reason is that the disk is full. "
              + "Please try to clean up your disk and try again.");
    }
  }
}
