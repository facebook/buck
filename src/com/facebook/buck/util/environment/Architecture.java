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

package com.facebook.buck.util.environment;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Represents the CPU architecture of a system. */
public enum Architecture {
  AARCH64("aarch64", "arm64"),
  ARM("arm"),
  ARMEB("armeb"),
  MIPS("mips"),
  MIPS64("mips64"),
  MIPSEL("mipsel"),
  MIPSEL64("mipsel64"),
  POWERPC("powerpc"),
  PPC64("ppc64"),
  UNKNOWN("unknown"),
  X86_32("x86_32", "i386", "i486", "i586", "i686", "i786", "x86"),
  X86_64("x86_64", "amd64");

  /** Maps names as used in the os.arch property to Architecture values. */
  private static Map<String, Architecture> nameToValueMap;

  static {
    nameToValueMap = new HashMap<>();
    for (Architecture arch : Architecture.values()) {
      nameToValueMap.put(arch.toString(), arch);
      for (String archName : arch.names) {
        nameToValueMap.put(archName, arch);
      }
    }
  }

  private final String name;
  private final String[] names;

  Architecture(String name, String... names) {
    this.name = name;
    this.names = names;
  }

  private static final Architecture HOST_ARCHITECTURE = detect(System.getProperties());

  /** Detect the host architecture from the given Java properties */
  public static Architecture detect(Properties properties) {
    String javaName = properties.getProperty("os.arch");
    return fromName(javaName);
  }

  /** @return CPU architecture of the currently running OS. */
  public static Architecture detect() {
    return HOST_ARCHITECTURE;
  }

  public static Architecture fromName(String name) {
    return nameToValueMap.getOrDefault(name, UNKNOWN);
  }

  @Override
  public String toString() {
    return name;
  }
}
