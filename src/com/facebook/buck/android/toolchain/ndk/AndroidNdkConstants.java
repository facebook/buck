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

package com.facebook.buck.android.toolchain.ndk;

public class AndroidNdkConstants {

  /**
   * Magic path prefix we use to denote the machine-specific location of the Android NDK. Why "@"?
   * It's uncommon enough to mark that path element as special while not being a metacharacter in
   * either make, shell, or regular expression syntax.
   *
   * <p>We also have prefixes for tool specific paths, even though they're sub-paths of
   * `@ANDROID_NDK_ROOT@`. This is to sanitize host-specific sub-directories in the toolchain (e.g.
   * darwin-x86_64) which would otherwise break determinism and caching when using
   * cross-compilation.
   */
  public static final String ANDROID_NDK_ROOT = "@ANDROID_NDK_ROOT@";
}
