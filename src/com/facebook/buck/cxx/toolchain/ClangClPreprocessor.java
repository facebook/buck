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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;

/** Preprocessor implementation for compilations using clang-cl. */
public class ClangClPreprocessor extends WindowsPreprocessor implements Preprocessor {

  private static final String FORWARD_FLAG_TO_CLANG = "-Xclang";
  private static final String CLANG_SYSTEM_INCLUDE_FLAG = "-isystem";

  public ClangClPreprocessor(Tool tool) {
    super(tool);
  }

  @Override
  public boolean supportsPrecompiledHeaders() {
    return true;
  }

  @Override
  // Clang-cl doesn't understand '/external:I' flag for system includes, so we have to pass
  // it '-isystem'. Other than that, clang-cl is pretty much drop in compatible with cl settings.
  public Iterable<String> systemIncludeArgs(Iterable<String> includeRoots) {
    return MoreIterables.zipAndConcat(
        Iterables.cycle(FORWARD_FLAG_TO_CLANG),
        Iterables.cycle(CLANG_SYSTEM_INCLUDE_FLAG),
        Iterables.cycle(FORWARD_FLAG_TO_CLANG),
        includeRoots);
  }

  @Override
  public Iterable<String> prefixHeaderArgs(Path prefixHeader) {
    return ImmutableList.of("/FI" + prefixHeader);
  }

  @Override
  public Iterable<String> precompiledHeaderArgs(Path pchOutputPath) {
    // Formally we should use MSVC flag /Yu to add precompiled header, but clang-cl doesn't
    // handle it properly. Therefore we had to fallback to the "clang" way using -Xclang
    // pass through flag.
    return ImmutableList.of("-Xclang", "-include-pch", "-Xclang", pchOutputPath.toString());
  }
}
