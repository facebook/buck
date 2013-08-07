/*
 * Copyright 2013-present Facebook, Inc.
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

public enum Platform {
  LINUX("Linux"),
  MACOS("OS X"),
  WINDOWS("Windows"),
  UNKNOWN("Unknown");

  private String platformName;

  Platform(String platformName) {
    this.platformName = platformName;
  }

  public String getPrintableName() {
    return platformName;
  }

  public static Platform detect() {
    String platformName = System.getProperty("os.name");
    if (platformName.startsWith("Linux")) {
      return LINUX;
    } else if (platformName.startsWith("Mac OS")) {
      return MACOS;
    } else if (platformName.startsWith("Windows")) {
      return WINDOWS;
    } else {
      return UNKNOWN;
    }
  }
}
