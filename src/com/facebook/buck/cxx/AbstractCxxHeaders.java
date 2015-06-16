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
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxHeaders implements RuleKeyAppendable {

  /**
   * List of headers that are implicitly included at the beginning of each preprocessed source file.
   */
  abstract List<SourcePath> getPrefixHeaders();

  /**
   * Maps the name of the header (e.g. the path used to include it in a C/C++ source) to the
   * real location of the header.
   */
  abstract Map<Path, SourcePath> getNameToPathMap();

  /**
   * Maps the full of the header (e.g. the path to the header that appears in error messages) to
   * the real location of the header.
   */
  abstract Map<Path, SourcePath> getFullNameToPathMap();

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    builder.setReflectively("prefixHeaders", getPrefixHeaders());

    for (Path path : ImmutableSortedSet.copyOf(getNameToPathMap().keySet())) {
      SourcePath source = getNameToPathMap().get(path);
      builder.setReflectively("include(" + path + ")", source);
    }

    return builder;
  }

  public static void addAllEntriesToIncludeMap(
      Map<Path, SourcePath> destination,
      Map<Path, SourcePath> source)
      throws ConflictingHeadersException {
    for (Map.Entry<Path, SourcePath> entry : source.entrySet()) {
      SourcePath original = destination.put(entry.getKey(), entry.getValue());
      if (original != null && !original.equals(entry.getValue())) {
        throw new ConflictingHeadersException(entry.getKey(), original, entry.getValue());
      }
    }
  }

  public static CxxHeaders concat(Iterable<CxxHeaders> headerGroup)
      throws ConflictingHeadersException {

    ImmutableList.Builder<SourcePath> prefixHeaders = ImmutableList.builder();
    Map<Path, SourcePath> nameToPathMap = Maps.newLinkedHashMap();
    Map<Path, SourcePath> fullNameToPathMap = Maps.newLinkedHashMap();

    for (CxxHeaders headers : headerGroup) {
      prefixHeaders.addAll(headers.getPrefixHeaders());
      addAllEntriesToIncludeMap(nameToPathMap, headers.getNameToPathMap());
      addAllEntriesToIncludeMap(fullNameToPathMap, headers.getFullNameToPathMap());
    }

    return CxxHeaders.builder()
        .setPrefixHeaders(prefixHeaders.build())
        .setNameToPathMap(nameToPathMap)
        .setFullNameToPathMap(fullNameToPathMap)
        .build();
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
