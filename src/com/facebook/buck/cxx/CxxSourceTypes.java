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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.CompilerProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PreprocessorProvider;
import com.google.common.collect.ImmutableList;

/** Utilities for working with C-like source types. */
public class CxxSourceTypes {
  // Utility class; do not instantiate.
  private CxxSourceTypes() {}

  /** Returns true for source types which can be run through the C preprocessor. */
  public static boolean isPreprocessableType(CxxSource.Type sourceType) {
    return sourceType == CxxSource.Type.C
        || sourceType == CxxSource.Type.CXX
        || sourceType == CxxSource.Type.ASSEMBLER_WITH_CPP
        || sourceType == CxxSource.Type.OBJC
        || sourceType == CxxSource.Type.OBJCXX
        || sourceType == CxxSource.Type.CUDA
        || sourceType == CxxSource.Type.HIP
        || sourceType == CxxSource.Type.ASM_WITH_CPP
        || sourceType == CxxSource.Type.PCM;
  }

  /**
   * Returns true for source types which can be built with the C compiler without a preprocessor.
   */
  public static boolean isCompilableType(CxxSource.Type sourceType) {
    return sourceType == CxxSource.Type.C_CPP_OUTPUT
        || sourceType == CxxSource.Type.CXX_CPP_OUTPUT
        || sourceType == CxxSource.Type.ASSEMBLER
        || sourceType == CxxSource.Type.OBJC_CPP_OUTPUT
        || sourceType == CxxSource.Type.OBJCXX_CPP_OUTPUT
        || sourceType == CxxSource.Type.CUDA_CPP_OUTPUT
        || sourceType == CxxSource.Type.HIP_CPP_OUTPUT
        || sourceType == CxxSource.Type.ASM
        || sourceType == CxxSource.Type.PCM;
  }

  /** @return the appropriate {@link Tool} representing the preprocessor. */
  public static PreprocessorProvider getPreprocessor(CxxPlatform cxxPlatform, CxxSource.Type type) {
    PreprocessorProvider preprocessor;

    switch (type) {
      case ASSEMBLER_WITH_CPP:
        preprocessor = cxxPlatform.getAspp();
        break;
      case C:
        preprocessor = cxxPlatform.getCpp();
        break;
      case CXX:
      case PCM:
        preprocessor = cxxPlatform.getCxxpp();
        break;
      case OBJC:
        preprocessor = cxxPlatform.getCpp();
        break;
      case OBJCXX:
        preprocessor = cxxPlatform.getCxxpp();
        break;
      case CUDA:
        if (!cxxPlatform.getCudapp().isPresent()) {
          throw new HumanReadableException("%s: no cuda preprocessor set", cxxPlatform.getFlavor());
        }
        preprocessor = cxxPlatform.getCudapp().get();
        break;
      case HIP:
        if (!cxxPlatform.getHippp().isPresent()) {
          throw new HumanReadableException("%s: no hip preprocessor set", cxxPlatform.getFlavor());
        }
        preprocessor = cxxPlatform.getHippp().get();
        break;
      case ASM_WITH_CPP:
        if (!cxxPlatform.getAsmpp().isPresent()) {
          throw new HumanReadableException("%s: no asm preprocessor set", cxxPlatform.getFlavor());
        }
        preprocessor = cxxPlatform.getAsmpp().get();
        break;
        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return preprocessor;
  }

  /** @return the platform-specific preprocessor flags for the given {@link CxxPlatform}. */
  public static ImmutableList<String> getPlatformPreprocessFlags(
      CxxPlatform cxxPlatform, CxxSource.Type type) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    switch (type) {
      case ASSEMBLER_WITH_CPP:
        flags.addAll(cxxPlatform.getAsppflags());
        break;
      case C:
        flags.addAll(cxxPlatform.getCppflags());
        break;
      case CXX:
      case PCM:
        flags.addAll(cxxPlatform.getCxxppflags());
        break;
      case OBJC:
        flags.addAll(cxxPlatform.getCppflags());
        break;
      case OBJCXX:
        flags.addAll(cxxPlatform.getCxxppflags());
        break;
      case CUDA:
        flags.addAll(cxxPlatform.getCudappflags());
        break;
      case HIP:
        flags.addAll(cxxPlatform.getHipppflags());
        break;
      case ASM_WITH_CPP:
        flags.addAll(cxxPlatform.getAsmppflags());
        break;
        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return flags.build();
  }

  /** @return the type produces from preprocessing the given input source type. */
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
      case CUDA:
        outputType = CxxSource.Type.CUDA_CPP_OUTPUT;
        break;
      case HIP:
        outputType = CxxSource.Type.HIP_CPP_OUTPUT;
        break;
      case ASM_WITH_CPP:
        outputType = CxxSource.Type.ASM;
        break;
      case PCM:
        outputType = CxxSource.Type.PCM;
        break;
        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return outputType;
  }

  /** @return whether this source type supports dep files. */
  public static boolean supportsDepFiles(CxxSource.Type type) {
    return type != CxxSource.Type.PCM;
  }

  /** @return the appropriate compiler for the given language type. */
  public static CompilerProvider getCompiler(CxxPlatform cxxPlatform, CxxSource.Type type) {
    CompilerProvider compiler;

    switch (type) {
      case ASSEMBLER:
        compiler = cxxPlatform.getAs();
        break;
      case C_CPP_OUTPUT:
        compiler = cxxPlatform.getCc();
        break;
      case CXX_CPP_OUTPUT:
      case PCM:
        compiler = cxxPlatform.getCxx();
        break;
      case OBJC_CPP_OUTPUT:
        compiler = cxxPlatform.getCc();
        break;
      case OBJCXX_CPP_OUTPUT:
        compiler = cxxPlatform.getCxx();
        break;
      case CUDA_CPP_OUTPUT:
        if (!cxxPlatform.getCuda().isPresent()) {
          throw new HumanReadableException("%s: no cuda compiler set", cxxPlatform.getFlavor());
        }
        compiler = cxxPlatform.getCuda().get();
        break;
      case HIP_CPP_OUTPUT:
        if (!cxxPlatform.getHip().isPresent()) {
          throw new HumanReadableException("%s: no hip compiler set", cxxPlatform.getFlavor());
        }
        compiler = cxxPlatform.getHip().get();
        break;
      case ASM:
        if (!cxxPlatform.getAsm().isPresent()) {
          throw new HumanReadableException("%s: no asm compiler set", cxxPlatform.getFlavor());
        }
        compiler = cxxPlatform.getAsm().get();
        break;
        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return compiler;
  }

  /** @return the platform-specific compiler flags for the given {@link CxxPlatform}. */
  public static ImmutableList<String> getPlatformCompilerFlags(
      CxxPlatform cxxPlatform, CxxSource.Type type) {

    ImmutableList.Builder<String> flags = ImmutableList.builder();

    switch (type) {
      case ASSEMBLER:
        flags.addAll(cxxPlatform.getAsflags());
        break;
      case C_CPP_OUTPUT:
        flags.addAll(cxxPlatform.getCflags());
        break;
      case CXX_CPP_OUTPUT:
      case PCM:
        flags.addAll(cxxPlatform.getCxxflags());
        break;
      case OBJC_CPP_OUTPUT:
        flags.addAll(cxxPlatform.getCflags());
        break;
      case OBJCXX_CPP_OUTPUT:
        flags.addAll(cxxPlatform.getCxxflags());
        break;
      case CUDA_CPP_OUTPUT:
        flags.addAll(cxxPlatform.getCudaflags());
        break;
      case HIP_CPP_OUTPUT:
        flags.addAll(cxxPlatform.getHipflags());
        break;
      case ASM:
        flags.addAll(cxxPlatform.getAsmflags());
        break;
        // $CASES-OMITTED$
      default:
        throw new IllegalStateException(String.format("unexpected type: %s", type));
    }

    return flags.build();
  }
}
