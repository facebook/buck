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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Platform on which Buck is currently running. Limited to the OS kind, e.g. Windows or a kind of
 * Unix (MACOS, LINUX, ...).
 */
public enum Platform {
  LINUX("Linux", "linux", PlatformType.UNIX),
  MACOS("OS X", "darwin", PlatformType.UNIX),
  WINDOWS("Windows", "windows", PlatformType.WINDOWS),
  FREEBSD("FreeBSD", "freebsd", PlatformType.UNIX),
  UNKNOWN("Unknown", "unknown", PlatformType.UNKNOWN);

  private String autoconfName;
  private String platformName;
  private PlatformType platformType;

  Platform(String platformName, String autoconfName, PlatformType platformType) {
    this.platformName = platformName;
    this.autoconfName = autoconfName;
    this.platformType = platformType;
  }

  /** @return platform name as used in autoconf target tuples */
  public String getAutoconfName() {
    return autoconfName;
  }

  public String getPrintableName() {
    return platformName;
  }

  public PlatformType getType() {
    return platformType;
  }

  /**
   * Return a path to a special file which accepts reads (always returning EOF) and writes (accepts
   * and immediately discards written data). E.g {@code /dev/null} on Unix and {@code NUL} on
   * Windows.
   */
  public Path getNullDevicePath() {
    if (getType().isUnix()) {
      // This should be a valid path on any Unix/POSIX-conforming system.
      // http://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap10.html
      return Paths.get("/dev/null");
    } else if (getType().isWindows()) {
      // MS docs on special filenames.  The file named `NUL` or even `NUL.ext` is a blackhole.
      // https://msdn.microsoft.com/en-us/library/windows/desktop/aa365247(v=vs.85).aspx
      return Paths.get("NUL");
    }
    throw new IllegalStateException(
        "don't know null special file for OS: " + System.getProperty("os.name"));
  }

  public static Platform detect() {
    String platformName = System.getProperty("os.name");
    if (platformName == null) {
      return UNKNOWN;
    } else if (platformName.startsWith("Linux")) {
      return LINUX;
    } else if (platformName.startsWith("Mac OS")) {
      return MACOS;
    } else if (platformName.startsWith("Windows")) {
      return WINDOWS;
    } else if (platformName.startsWith("FreeBSD")) {
      return FREEBSD;
    } else {
      return UNKNOWN;
    }
  }
}
