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
import com.facebook.buck.util.environment.Architecture;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Represents the GOARCH values in Go found at:
 * https://github.com/golang/go/blob/master/src/go/build/syslist.go
 */
public enum GoArch {
  I386("386", Architecture.I386),
  AMD64("amd64", Architecture.X86_64),
  AMD64P32("amd64p32", Architecture.UNKNOWN),
  ARM("arm", Architecture.ARM),
  ARMV5("armv5", Architecture.ARM),
  ARMv6("armv6", Architecture.ARM),
  ARMV7("armv7", Architecture.ARM),
  ARMBE("armbe", Architecture.ARMEB),
  ARM64("arm64", Architecture.AARCH64),
  ARM64BE("arm64be", Architecture.UNKNOWN),
  PPC64("ppc64", Architecture.PPC64),
  PPC64LE("ppc64le", Architecture.UNKNOWN),
  MIPS("mips", Architecture.MIPS),
  MIPSLE("mipsle", Architecture.MIPSEL),
  MIPS64("mips64", Architecture.MIPS64),
  MIPS64LE("mips64le", Architecture.MIPSEL64),
  MIPS64P32("mips64p32", Architecture.UNKNOWN),
  MIPS64P32LE("mips64p32le", Architecture.UNKNOWN),
  PPC("ppc", Architecture.POWERPC),
  RISCV("riscv", Architecture.UNKNOWN),
  RISCV64("riscv64", Architecture.UNKNOWN),
  S390("s390", Architecture.UNKNOWN),
  S390X("s390x", Architecture.UNKNOWN),
  SPARC("sparc", Architecture.UNKNOWN),
  SPARC64("sparc64", Architecture.UNKNOWN),
  WASM("wasm", Architecture.UNKNOWN);

  private String name;
  private Architecture arch;

  private static final Map<String, GoArch> map = new HashMap<String, GoArch>();
  private static final Map<Architecture, GoArch> archMap = new HashMap<Architecture, GoArch>();

  static {
    for (GoArch goarch : GoArch.values()) {
      map.put(goarch.name, goarch);

      if (goarch.arch != Architecture.UNKNOWN) {
        archMap.put(goarch.arch, goarch);
      }
    }
  }

  GoArch(String name, Architecture arch) {
    this.name = name;
    this.arch = arch;
  }

  /** Returns the environment variable to be used for GOARCH to Go tools. */
  public String getEnvVarValue() {
    return name.startsWith("armv") ? "arm" : name;
  }

  /** Returns the environment variable to be used for GOARM to Go tools. */
  public String getEnvVarValueForArm() {
    return name.startsWith("armv") ? name.substring(name.length() - 1) : "";
  }

  /**
   * Finds the GoArch from it's name.
   *
   * @param name name of the GoArch as defined from Go itself
   * @return GoArch for the matching name
   * @throws NoSuchElementException when a GoArch is not found for the name
   */
  public static GoArch fromString(String name) {
    if (map.containsKey(name)) {
      return map.get(name);
    }
    throw new NoSuchElementException("No GOARCH found for name '" + name + "'");
  }

  /**
   * returns the corresponding GoOs for a given Architecture
   *
   * @param arch the {@link Architecture} to lookup
   * @return GoArch for the matching platform
   * @throws HumanReadableException when a specific GoArch is not found for the Architecture
   */
  public static GoArch fromArchitecture(Architecture arch) throws HumanReadableException {
    if (archMap.containsKey(arch)) {
      return archMap.get(arch);
    }
    throw new HumanReadableException("No GOARCH found for platform '%s'", arch);
  }
}
