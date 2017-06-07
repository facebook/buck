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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public interface Preprocessor extends Tool {

  Optional<ImmutableList<String>> getFlagsForColorDiagnostics();

  boolean supportsHeaderMaps();

  boolean supportsPrecompiledHeaders();

  Iterable<String> localIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> systemIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> quoteIncludeArgs(Iterable<String> includeRoots);

  Iterable<String> precompiledHeaderArgs(Path pchOutputPath);

  Iterable<String> prefixHeaderArgs(SourcePathResolver resolver, SourcePath prefixHeader);

  /**
   * @param prefixHeader the {@code prefix_hedaer} param for the rule.
   * @param pchOutputPath either a {@code precompiled_header} path, or the result of precompiling
   *     {@code prefixHeader}. Not mutually exclusive with {@code prefixHeader}; if both are given,
   *     the precompiled version of it is preferred.
   */
  default Iterable<String> prefixOrPCHArgs(
      SourcePathResolver resolver,
      Optional<SourcePath> prefixHeader,
      Optional<Path> pchOutputPath) {
    ImmutableList.Builder<String> builder = ImmutableList.<String>builder();
    if (pchOutputPath.isPresent()) {
      Preconditions.checkState(
          this.supportsPrecompiledHeaders(),
          "Precompiled header was requested, but is not supported by " + getClass().toString());
      builder.addAll(precompiledHeaderArgs(pchOutputPath.get()));
    } else if (prefixHeader.isPresent()) {
      builder.addAll(prefixHeaderArgs(resolver, prefixHeader.get()));
    }
    return builder.build();
  }
}
