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
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

import java.nio.file.Path;
import java.util.Map;

/**
 * The components that get contributed to a top-level run of the C++ preprocessor.
 */
public class CxxPreprocessorInput {

  // The build rules which produce headers found in the includes below.
  private final ImmutableSet<BuildTarget> rules;

  private final ImmutableMultimap<CxxSource.Type, String> preprocessorFlags;

  private final ImmutableMap<Path, SourcePath> includes;

  // Normal include directories where headers are found.
  private final ImmutableList<Path> includeRoots;

  // Include directories where system headers.
  private final ImmutableList<Path> systemIncludeRoots;

  private CxxPreprocessorInput(
      ImmutableSet<BuildTarget> rules,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableMap<Path, SourcePath> includes,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots) {
    this.rules = Preconditions.checkNotNull(rules);
    this.preprocessorFlags = Preconditions.checkNotNull(preprocessorFlags);
    this.includes = Preconditions.checkNotNull(includes);
    this.includeRoots = Preconditions.checkNotNull(includeRoots);
    this.systemIncludeRoots = Preconditions.checkNotNull(systemIncludeRoots);
  }

  /**
   * Builder used to construct {@link CxxPreprocessorInput} instances.
   */
  public static class Builder {
    private ImmutableSet<BuildTarget> rules = ImmutableSet.of();
    private ImmutableMultimap<CxxSource.Type, String> preprocessorFlags = ImmutableMultimap.of();
    private ImmutableMap<Path, SourcePath> includes = ImmutableMap.of();
    private ImmutableList<Path> includeRoots = ImmutableList.of();
    private ImmutableList<Path> systemIncludeRoots = ImmutableList.of();

    public CxxPreprocessorInput build() {
      return new CxxPreprocessorInput(
          rules,
          preprocessorFlags,
          includes,
          includeRoots,
          systemIncludeRoots);
    }

    public Builder setRules(Iterable<BuildTarget> rules) {
      this.rules = ImmutableSet.copyOf(rules);
      return this;
    }

    public Builder setPreprocessorFlags(Multimap<CxxSource.Type, String> preprocessorFlags) {
      this.preprocessorFlags = ImmutableMultimap.copyOf(preprocessorFlags);
      return this;
    }

    public Builder setIncludes(Map<Path, SourcePath> includes) {
      this.includes = ImmutableMap.copyOf(includes);
      return this;
    }

    public Builder setIncludeRoots(Iterable<Path> includeRoots) {
      this.includeRoots = ImmutableList.copyOf(includeRoots);
      return this;
    }

    public Builder setSystemIncludeRoots(Iterable<Path> systemIncludeRoots) {
      this.systemIncludeRoots = ImmutableList.copyOf(systemIncludeRoots);
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final CxxPreprocessorInput EMPTY = builder().build();

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs) {
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();
    ImmutableMultimap.Builder<CxxSource.Type, String> preprocessorFlags =
      ImmutableMultimap.builder();
    ImmutableMap.Builder<Path, SourcePath> includes = ImmutableMap.builder();
    ImmutableList.Builder<Path> includeRoots = ImmutableList.builder();
    ImmutableList.Builder<Path> systemIncludeRoots = ImmutableList.builder();

    for (CxxPreprocessorInput input : inputs) {
      rules.addAll(input.getRules());
      preprocessorFlags.putAll(input.getPreprocessorFlags());
      includes.putAll(input.getIncludes());
      includeRoots.addAll(input.getIncludeRoots());
      systemIncludeRoots.addAll(input.getSystemIncludeRoots());
    }

    return new CxxPreprocessorInput(
        rules.build(),
        preprocessorFlags.build(),
        includes.build(),
        includeRoots.build(),
        systemIncludeRoots.build());
  }

  public ImmutableSet<BuildTarget> getRules() {
    return rules;
  }

  public ImmutableMultimap<CxxSource.Type, String> getPreprocessorFlags() {
    return preprocessorFlags;
  }

  public ImmutableMap<Path, SourcePath> getIncludes() {
    return includes;
  }

  public ImmutableList<Path> getIncludeRoots() {
    return includeRoots;
  }

  public ImmutableList<Path> getSystemIncludeRoots() {
    return systemIncludeRoots;
  }

  @Override
  public boolean equals(Object o) {

    if (this == o) {
      return true;
    }

    if (!(o instanceof CxxPreprocessorInput)) {
      return false;
    }

    CxxPreprocessorInput that = (CxxPreprocessorInput) o;

    if (!rules.equals(that.rules)) {
      return false;
    }

    if (!preprocessorFlags.equals(that.preprocessorFlags)) {
      return false;
    }

    if (!includes.equals(that.includes)) {
      return false;
    }

    if (!includeRoots.equals(that.includeRoots)) {
      return false;
    }

    if (!systemIncludeRoots.equals(that.systemIncludeRoots)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        rules,
        preprocessorFlags,
        includes,
        includeRoots,
        systemIncludeRoots);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("rules", rules)
        .add("preprocessorFlags", preprocessorFlags)
        .add("includes", includes)
        .add("includeRoots", includeRoots)
        .add("systemIncludeRoots", systemIncludeRoots)
        .toString();
  }

}
