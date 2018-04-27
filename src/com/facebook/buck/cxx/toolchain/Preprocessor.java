/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.toolchain.tool.Tool;
import java.nio.file.Path;

/** Interface for a c/c++ preprocessor. */
public interface Preprocessor extends Tool {

  boolean supportsHeaderMaps();

  boolean supportsPrecompiledHeaders();

  Iterable<String> localIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> systemIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> quoteIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> precompiledHeaderArgs(Path pchOutputPath);

  Iterable<String> prefixHeaderArgs(Path prefixHeader);

  /**
   * @param prefixHeader the {@code prefix_hedaer} param for the rule.
   * @param pchOutputPath either a {@code precompiled_header} path, or the result of precompiling
   *     {@code prefixHeader}. Not mutually exclusive with {@code prefixHeader}; if both are given,
   *     the precompiled version of it is preferred.
   */
  default Iterable<String> prefixOrPCHArgs(boolean precompiled, Path path) {
    return precompiled ? precompiledHeaderArgs(path) : prefixHeaderArgs(path);
  }
}
