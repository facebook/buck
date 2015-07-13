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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The components that get contributed to a top-level run of the C++ preprocessor.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxPreprocessorInput {

  public static final Function<CxxPreprocessorInput, CxxHeaders> GET_INCLUDES =
      new Function<CxxPreprocessorInput, CxxHeaders>() {
        @Override
        public CxxHeaders apply(CxxPreprocessorInput input) {
          return input.getIncludes();
        }
      };

  public static final Function<CxxPreprocessorInput, ImmutableSet<Path>> GET_INCLUDE_ROOTS =
      new Function<CxxPreprocessorInput, ImmutableSet<Path>>() {
        @Override
        public ImmutableSet<Path> apply(CxxPreprocessorInput input) {
          return input.getIncludeRoots();
        }
      };

  public static final Function<CxxPreprocessorInput, ImmutableSet<Path>> GET_SYSTEM_INCLUDE_ROOTS =
      new Function<CxxPreprocessorInput, ImmutableSet<Path>>() {
        @Override
        public ImmutableSet<Path> apply(CxxPreprocessorInput input) {
          return input.getSystemIncludeRoots();
        }
      };

  public static final Function<CxxPreprocessorInput, ImmutableSet<Path>> GET_FRAMEWORK_ROOTS =
      new Function<CxxPreprocessorInput, ImmutableSet<Path>>() {
        @Override
        public ImmutableSet<Path> apply(CxxPreprocessorInput input) {
          return input.getFrameworkRoots();
        }
      };

  // The build rules which produce headers found in the includes below.
  @Value.Parameter
  public abstract Set<BuildTarget> getRules();

  @Value.Parameter
  public abstract Multimap<CxxSource.Type, String> getPreprocessorFlags();

  @Value.Parameter
  @Value.Default
  public CxxHeaders getIncludes() {
    return CxxHeaders.builder().build();
  }

  // Normal include directories where headers are found.
  @Value.Parameter
  public abstract Set<Path> getIncludeRoots();

  // Include directories where system headers.
  @Value.Parameter
  public abstract Set<Path> getSystemIncludeRoots();

  // Directories where frameworks are stored.
  @Value.Parameter
  public abstract Set<Path> getFrameworkRoots();

  public static final CxxPreprocessorInput EMPTY = CxxPreprocessorInput.builder().build();

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs)
      throws AbstractCxxHeaders.ConflictingHeadersException {
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();
    ImmutableMultimap.Builder<CxxSource.Type, String> preprocessorFlags =
      ImmutableMultimap.builder();
    ImmutableList.Builder<SourcePath> prefixHeaders = ImmutableList.builder();
    Map<Path, SourcePath> includeNameToPathMap = new HashMap<>();
    Map<Path, SourcePath> includeFullNameToPathMap = new HashMap<>();
    ImmutableSet.Builder<Path> includeRoots = ImmutableSet.builder();
    ImmutableSet.Builder<Path> systemIncludeRoots = ImmutableSet.builder();
    ImmutableSet.Builder<Path> frameworkRoots = ImmutableSet.builder();

    for (CxxPreprocessorInput input : inputs) {
      rules.addAll(input.getRules());
      preprocessorFlags.putAll(input.getPreprocessorFlags());
      prefixHeaders.addAll(input.getIncludes().getPrefixHeaders());
      CxxHeaders.addAllEntriesToIncludeMap(
          includeNameToPathMap,
          input.getIncludes().getNameToPathMap());
      CxxHeaders.addAllEntriesToIncludeMap(
          includeFullNameToPathMap,
          input.getIncludes().getFullNameToPathMap());
      includeRoots.addAll(input.getIncludeRoots());
      systemIncludeRoots.addAll(input.getSystemIncludeRoots());
      frameworkRoots.addAll(input.getFrameworkRoots());
    }

    return CxxPreprocessorInput.of(
        rules.build(),
        preprocessorFlags.build(),
        CxxHeaders.builder()
            .addAllPrefixHeaders(prefixHeaders.build())
            .putAllNameToPathMap(includeNameToPathMap)
            .putAllFullNameToPathMap(includeFullNameToPathMap)
            .build(),
        includeRoots.build(),
        systemIncludeRoots.build(),
        frameworkRoots.build());
  }

}
