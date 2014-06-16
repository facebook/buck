/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.google.common.collect.ImmutableSet;

/**
 * File extensions used in Apple targets.
 */
public final class FileExtensions {

  // Utility class. Do not instantiate.
  private FileExtensions() { }

  /**
   * Source files compiled with Clang.
   */
  public static final ImmutableSet<String> CLANG_SOURCES =
    ImmutableSet.of("c", "cc", "cpp", "cxx", "m", "mm", "C", "cp", "CPP", "c++");

  /**
   * Header files for the above source file types.
   */
  public static final ImmutableSet<String> CLANG_HEADERS =
    ImmutableSet.of("h", "hh", "hpp", "hxx", "H", "hp", "HPP", "h++", "tcc");
}
