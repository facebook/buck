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

package com.facebook.buck.android.bundle;

import com.google.common.collect.ImmutableMap;

/**
 * This class holds a mapping from String name of Abi to corresponding numbers and a method to
 * retrieve number from string.
 */
class GetAbiByName {

  private static final ImmutableMap<String, Integer> ABI_BY_NAME =
      new ImmutableMap.Builder<String, Integer>()
          .put("UNSPECIFIED_CPU_ARCHITECTURE", 0)
          .put("ARMEABI", 1)
          .put("ARMEABI-V7A", 2)
          .put("ARM64-V8A", 3)
          .put("X86", 4)
          .put("X86-64", 5)
          .put("MIPS", 6)
          .put("MIPS64", 7)
          .build();

  /**
   * This method takes the path name as input and returns corresponding Abi number, if there is any.
   *
   * @param abiName the path name which potentially contains a Abi name
   * @return if the directory name contains a Abi name, then returns its corresponding number,
   *     otherwise return -1;
   */
  public static int getAbi(String abiName) {
    return ABI_BY_NAME.getOrDefault(abiName.toUpperCase(), -1);
  }
}
