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

package com.facebook.buck.util.environment;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Platform on which Buck is currently running. Limited to the OS kind, e.g. Windows or a kind of
 * Unix (MACOS, LINUX, ...).
 */
public enum Platform {
  LINUX("Linux", "Linux", "linux", "linux", PlatformType.UNIX),
  MACOS("OS X", "Mac OS X", "darwin", "osx", PlatformType.UNIX),
  WINDOWS("Windows", "Windows", "windows", "windows", PlatformType.WINDOWS),
  FREEBSD("FreeBSD", "FreeBSD", "freebsd", "freebsd", PlatformType.UNIX),
  UNKNOWN("Unknown", "Unknown", "unknown", "unknown", PlatformType.UNKNOWN);

  /**
   * Name that is used for debugging purposes. This is different from {@link #platformName} due to
   * historical reasons.
   *
   * <p>TODO: remove this field is favor of {@link #platformName}
   */
  private final String printableName;

  private final String autoconfName;
  private final String canonicalName;
  private final String platformName;
  private final PlatformType platformType;

  Platform(
      String printableName,
      String platformName,
      String autoconfName,
      String canonicalName,
      PlatformType platformType) {
    this.printableName = printableName;
    this.platformName = platformName;
    this.autoconfName = autoconfName;
    this.canonicalName = canonicalName;
    this.platformType = platformType;
  }

  /** @return platform name as used in autoconf target tuples */
  public String getAutoconfName() {
    return autoconfName;
  }

  public String getPrintableName() {
    return printableName;
  }

  public PlatformType getType() {
    return platformType;
  }

  public String getCanonicalName() {
    return canonicalName;
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

  private static final Platform HOST_PLATFORM = detect(System.getProperties());

  private static Platform detect(Properties properties) {
    String platformName = properties.getProperty("os.name");
    if (platformName == null) {
      return UNKNOWN;
    }

    for (Platform platform : values()) {
      if (platformName.startsWith(platform.platformName)) {
        return platform;
      }
    }
    return UNKNOWN;
  }

  public static Platform detect() {
    return HOST_PLATFORM;
  }
}
