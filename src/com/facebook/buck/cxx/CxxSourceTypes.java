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


import com.google.common.collect.ImmutableList;

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
    return
        sourceType == CxxSource.Type.C ||
        sourceType == CxxSource.Type.CXX ||
        sourceType == CxxSource.Type.ASSEMBLER_WITH_CPP ||
        sourceType == CxxSource.Type.OBJC ||
        sourceType == CxxSource.Type.OBJCXX;
  }

  /**
   * Returns true for source types which can be run through the C preprocessor.
   */
  public static boolean isCompilableType(CxxSource.Type sourceType) {
    return
        sourceType == CxxSource.Type.C_CPP_OUTPUT ||
        sourceType == CxxSource.Type.CXX_CPP_OUTPUT ||
        sourceType == CxxSource.Type.ASSEMBLER ||
        sourceType == CxxSource.Type.OBJC_CPP_OUTPUT ||
        sourceType == CxxSource.Type.OBJCXX_CPP_OUTPUT;
  }

  /**
   * Returns true for source types which need to be built with a C++ compiler.
   */
  public static boolean needsCxxCompiler(CxxSource.Type sourceType) {
    return
        sourceType == CxxSource.Type.CXX ||
        sourceType == CxxSource.Type.CXX_CPP_OUTPUT ||
        sourceType == CxxSource.Type.OBJCXX ||
        sourceType == CxxSource.Type.OBJCXX_CPP_OUTPUT;
  }

  /**
   * @return the appropriate {@link Tool} representing the preprocessor.
   */
  public static Tool getPreprocessor(CxxPlatform cxxPlatform, CxxSource.Type type) {
    Tool preprocessor;

    switch (type) {
      case ASSEMBLER_WITH_CPP:
        preprocessor = cxxPlatform.getAspp();
        break;
      case C:
        preprocessor = cxxPlatform.getCpp();
        break;
      case CXX:
        preprocessor = cxxPlatform.getCxxpp();
        break;
      case OBJC:
        preprocessor = cxxPlatform.getCpp();
        break;
      case OBJCXX:
        preprocessor = cxxPlatform.getCxxpp();
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return preprocessor;
  }

  /**
   * @return the platform-specific preprocessor flags for the given {@link CxxPlatform}.
   */
  public static ImmutableList<String> getPlatformPreprocessFlags(
      CxxPlatform cxxPlatform,
      CxxSource.Type type) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    switch (type) {
      case ASSEMBLER_WITH_CPP:
        flags.addAll(cxxPlatform.getAsppflags());
        break;
      case C:
        flags.addAll(cxxPlatform.getCppflags());
        break;
      case CXX:
        flags.addAll(cxxPlatform.getCxxppflags());
        break;
      case OBJC:
        flags.addAll(cxxPlatform.getCppflags());
        break;
      case OBJCXX:
        flags.addAll(cxxPlatform.getCxxppflags());
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return flags.build();
  }

  /**
   * @return the type produces from preprocessing the given input source type.
   */
  public static CxxSource.Type getPreprocessorOutputType(CxxSource.Type type) {
    CxxSource.Type outputType;

    switch (type) {
      case ASSEMBLER_WITH_CPP:
        outputType = CxxSource.Type.ASSEMBLER;
        break;
      case C:
        outputType = CxxSource.Type.C_CPP_OUTPUT;
        break;
      case CXX:
        outputType = CxxSource.Type.CXX_CPP_OUTPUT;
        break;
      case OBJC:
        outputType = CxxSource.Type.OBJC_CPP_OUTPUT;
        break;
      case OBJCXX:
        outputType = CxxSource.Type.OBJCXX_CPP_OUTPUT;
        break;
      // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return outputType;
  }

}
