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
package com.facebook.buck.cxx.toolchain;

/** Enumerates possible external tools used in building C/C++ programs. */
public enum ToolType {
  AR("ar", "arflags"),
  AS("as", "asflags"),
  ASM("asm", "asmflags"),
  ASMPP("asmpp", "asmppflags"),
  ASPP("aspp", "asppflags"),
  CC("cc", "cflags"),
  CPP("cpp", "cppflags"),
  CUDA("cuda", "cudaflags"),
  CUDAPP("cudapp", "cudappflags"),
  CXX("cxx", "cxxflags"),
  CXXPP("cxxpp", "cxxppflags"),
  HIP("hip", "hipflags"),
  HIPPP("hippp", "hipppflags"),
  LD("ld", "ldflags"),
  RANLIB("ranlib", "ranlibflags"),
  ;

  /** Buck config key used to specify tool path. */
  public final String key;
  /** Buck config key used to specify tool flags. */
  public final String flagsKey;

  ToolType(String key, String flagsKey) {
    this.key = key;
    this.flagsKey = flagsKey;
  }
}
