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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The components that get contributed to a top-level run of the C++ preprocessor.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class CxxPreprocessorInput {

  // The build rules which produce headers found in the includes below.
  @Value.Parameter
  public abstract Set<BuildTarget> getRules();

  @Value.Parameter
  public abstract Multimap<CxxSource.Type, String> getPreprocessorFlags();

  @Value.Parameter
  @Value.Default
  public CxxHeaders getIncludes() {
    return ImmutableCxxHeaders.builder().build();
  }

  // Normal include directories where headers are found.
  @Value.Parameter
  public abstract List<Path> getIncludeRoots();

  // Include directories where system headers.
  @Value.Parameter
  public abstract List<Path> getSystemIncludeRoots();

  // Directories where frameworks are stored.
  @Value.Parameter
  public abstract List<Path> getFrameworkRoots();

  public static final CxxPreprocessorInput EMPTY = ImmutableCxxPreprocessorInput.builder().build();

  public static ImmutableCxxPreprocessorInput.Builder builder() {
    return ImmutableCxxPreprocessorInput.builder();
  }

  private static void addAllEntriesToIncludeMap(
      Map<Path, SourcePath> destination,
      Map<Path, SourcePath> source) throws ConflictingHeadersException {
    for (Map.Entry<Path, SourcePath> entry : source.entrySet()) {
      SourcePath original = destination.put(entry.getKey(), entry.getValue());
      if (original != null && !original.equals(entry.getValue())) {
        throw new ConflictingHeadersException(entry.getKey(), original, entry.getValue());
      }
    }
  }

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs)
      throws ConflictingHeadersException {
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();
    ImmutableMultimap.Builder<CxxSource.Type, String> preprocessorFlags =
      ImmutableMultimap.builder();
    ImmutableList.Builder<SourcePath> prefixHeaders = ImmutableList.builder();
    Map<Path, SourcePath> includeNameToPathMap = new HashMap<>();
    Map<Path, SourcePath> includeFullNameToPathMap = new HashMap<>();
    ImmutableList.Builder<Path> includeRoots = ImmutableList.builder();
    ImmutableList.Builder<Path> systemIncludeRoots = ImmutableList.builder();
    ImmutableList.Builder<Path> frameworkRoots = ImmutableList.builder();

    for (CxxPreprocessorInput input : inputs) {
      rules.addAll(input.getRules());
      preprocessorFlags.putAll(input.getPreprocessorFlags());
      prefixHeaders.addAll(input.getIncludes().getPrefixHeaders());
      addAllEntriesToIncludeMap(
          includeNameToPathMap,
          input.getIncludes().getNameToPathMap());
      addAllEntriesToIncludeMap(
          includeFullNameToPathMap,
          input.getIncludes().getFullNameToPathMap());
      includeRoots.addAll(input.getIncludeRoots());
      systemIncludeRoots.addAll(input.getSystemIncludeRoots());
      frameworkRoots.addAll(input.getFrameworkRoots());
    }

    return ImmutableCxxPreprocessorInput.of(
        rules.build(),
        preprocessorFlags.build(),
        ImmutableCxxHeaders.builder()
            .addAllPrefixHeaders(prefixHeaders.build())
            .putAllNameToPathMap(includeNameToPathMap)
            .putAllFullNameToPathMap(includeFullNameToPathMap)
            .build(),
        includeRoots.build(),
        systemIncludeRoots.build(),
        frameworkRoots.build());
  }

  @SuppressWarnings("serial")
  public static class ConflictingHeadersException extends Exception {
    public ConflictingHeadersException(Path key, SourcePath value1, SourcePath value2) {
      super(
          String.format(
              "'%s' maps to both %s.",
              key,
              ImmutableSortedSet.of(value1, value2)));
    }

    public HumanReadableException getHumanReadableExceptionForBuildTarget(BuildTarget buildTarget) {
      return new HumanReadableException(
          this,
          "Target '%s' uses conflicting header file mappings. %s",
          buildTarget,
          getMessage());
    }
  }

}
