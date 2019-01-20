/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.ndk;

import java.util.Objects;

/** The toolchains name for the platform being targeted. */
public enum NdkToolchainTarget {
  I686_LINUX_ANDROID("i686-linux-android"),
  X86_64_LINUX_ANDROID("x86_64-linux-android"),
  ARM_LINUX_ANDROIDEABI("arm-linux-androideabi"),
  AARCH64_LINUX_ANDROID("aarch64-linux-android"),
  ;

  private final String value;

  NdkToolchainTarget(String value) {
    this.value = Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
