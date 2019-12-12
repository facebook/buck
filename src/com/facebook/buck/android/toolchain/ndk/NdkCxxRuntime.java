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

package com.facebook.buck.android.toolchain.ndk;

/** The C/C++ runtime library to link against. */
public enum NdkCxxRuntime {
  SYSTEM("system", "system", "system"),
  GABIXX("gabi++", "gabi++_shared", "gabi++_static"),
  STLPORT("stlport", "stlport_shared", "stlport_static"),
  GNUSTL("gnu-libstdc++", "gnustl_shared", "gnustl_static"),
  LIBCXX("llvm-libc++", "c++_shared", "c++_static"),
  ;

  public final String name;
  public final String sharedName;
  public final String staticName;

  /**
   * @param name the runtimes directory name in the NDK.
   * @param sharedName the shared library name used for this runtime.
   * @param staticName the the static library used for this runtime.
   */
  NdkCxxRuntime(String name, String sharedName, String staticName) {
    this.name = name;
    this.sharedName = sharedName;
    this.staticName = staticName;
  }

  public String getSoname() {
    return "lib" + sharedName + ".so";
  }
}
