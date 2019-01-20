/*
 * Copyright 2018-present Facebook, Inc.
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

import java.util.Objects;

/** Utility class for retrieving Java version information. */
public class JavaVersion {
  private static int majorVersion = 0;

  /** Returns the major version (e.g. 8 for Java 1.8, and 10 for Java 10.0). */
  public static int getMajorVersion() {
    if (majorVersion == 0) {
      String[] versionParts =
          Objects.requireNonNull(System.getProperty("java.version")).split("\\.");
      majorVersion =
          Integer.parseInt((versionParts[0].equals("1")) ? versionParts[1] : versionParts[0]);
    }
    return majorVersion;
  }
}
