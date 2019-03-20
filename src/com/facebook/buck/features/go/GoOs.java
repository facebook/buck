/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.go;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Represents the GOOS values in Go found at:
 * https://github.com/golang/go/blob/master/src/go/build/syslist.go
 */
public enum GoOs {
  AIX("aix", Platform.UNKNOWN),
  ANDROID("android", Platform.UNKNOWN),
  DARWIN("darwin", Platform.MACOS),
  DRAGONFLY("dragonfly", Platform.UNKNOWN),
  FREEBSD("freebsd", Platform.UNKNOWN),
  HURD("hurd", Platform.UNKNOWN),
  JS("js", Platform.UNKNOWN),
  LINUX("linux", Platform.LINUX),
  NACL("nacl", Platform.UNKNOWN),
  NETBSD("netbsd", Platform.UNKNOWN),
  OPENBSD("openbsd", Platform.UNKNOWN),
  PLAN9("plan9", Platform.UNKNOWN),
  SOLARIS("solaris", Platform.UNKNOWN),
  WINDOWS("windows", Platform.WINDOWS),
  ZOS("zos", Platform.UNKNOWN);

  private String name;
  private Platform platform;

  private static final Map<Platform, GoOs> platMap = new HashMap<Platform, GoOs>();

  static {
    for (GoOs goos : GoOs.values()) {
      if (goos.platform != Platform.UNKNOWN) {
        platMap.put(goos.platform, goos);
      }
    }
  }

  GoOs(String name, Platform platform) {
    this.name = name;
    this.platform = platform;
  }

  /** Returns the environment variable to be used for GOOS to Go tools. */
  public String getEnvVarValue() {
    return name;
  }

  /**
   * Finds the GoOs from it's name.
   *
   * @param name name of the GoOS as defined from Go itself
   * @return GoOs for the matching name
   */
  public static GoOs fromString(String name) throws NoSuchElementException {
    return GoOs.valueOf(name.toUpperCase());
  }

  /**
   * returns the corresponding GoOs for a given Platform
   *
   * @param platform the {@link Platform} to lookup
   * @return GoOs for the matching platform
   * @throws HumanReadableException when a specific GoOS is not found for the Platform
   */
  public static GoOs fromPlatform(Platform platform) throws HumanReadableException {
    if (platMap.containsKey(platform)) {
      return platMap.get(platform);
    }
    throw new HumanReadableException("No GOOS found for platform '%s'", platform);
  }
}
