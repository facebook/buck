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

package com.facebook.buck.cxx;

import com.google.common.base.Preconditions;

/**
 * Utilities for working with C-like source types.
 */
public class CxxSourceTypes {
  // Utility class; do not instantiate.
  private CxxSourceTypes() { }

  /**
   * Returns true for source types which can be run through the C preprocessor.
   */
  public static boolean isPreprocessableType(CxxSource.Type sourceType) {
    Preconditions.checkNotNull(sourceType);
    return
        sourceType == CxxSource.Type.C ||
        sourceType == CxxSource.Type.CXX ||
        sourceType == CxxSource.Type.ASSEMBLER_WITH_CPP ||
        sourceType == CxxSource.Type.OBJC ||
        sourceType == CxxSource.Type.OBJCXX;
  }

  /**
   * Returns true for source types which need to be built with a C++ compiler.
   */
  public static boolean needsCxxCompiler(CxxSource.Type sourceType) {
    Preconditions.checkNotNull(sourceType);
    return
        sourceType == CxxSource.Type.CXX ||
        sourceType == CxxSource.Type.CXX_CPP_OUTPUT ||
        sourceType == CxxSource.Type.OBJCXX ||
        sourceType == CxxSource.Type.OBJCXX_CPP_OUTPUT;
  }
}
