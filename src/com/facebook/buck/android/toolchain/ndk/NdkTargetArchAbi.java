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

/** Name of the target CPU + ABI. */
public enum NdkTargetArchAbi {
  X86("x86"),
  X86_64("x86_64"),
  ARMEABI("armeabi"),
  ARMEABI_V7A("armeabi-v7a"),
  ARM64_V8A("arm64-v8a"),
  ;

  private final String value;

  NdkTargetArchAbi(String value) {
    this.value = Objects.requireNonNull(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
