/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.version;

import java.util.Objects;

/** Utility class for retrieving Java version information. */
public class JavaVersion {
  private static int majorVersion = 0;

  /**
   * Returns the major version of the current JVM instance (e.g. 8 for Java 1.8, and 10 for Java
   * 10.0).
   */
  public static int getMajorVersion() {
    if (majorVersion == 0) {
      majorVersion =
          getMajorVersionFromString(Objects.requireNonNull(System.getProperty("java.version")));
    }
    return majorVersion;
  }

  /** Returns the major version from a Java version string (e.g. 8 for "1.8", and 10 for "10.0"). */
  public static int getMajorVersionFromString(String version) {
    String[] versionParts = Objects.requireNonNull(version).split("\\.");
    return Integer.parseInt((versionParts[0].equals("1")) ? versionParts[1] : versionParts[0]);
  }
}
