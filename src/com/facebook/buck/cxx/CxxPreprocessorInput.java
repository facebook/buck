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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

/**
 * The components that get contributed to a top-level run of the C++ preprocessor.
 */
public class CxxPreprocessorInput {

  // The build rules which produce headers found in the includes below.
  private final ImmutableSet<BuildTarget> rules;

  // The build rules which produce headers found in the includes below.
  private final ImmutableList<String> cppflags;

  // The build rules which produce headers found in the includes below.
  private final ImmutableList<String> cxxppflags;

  private final ImmutableMap<Path, SourcePath> includes;

  // Normal include directories where headers are found.
  private final ImmutableList<Path> includeRoots;

  // Include directories where system headers.
  private final ImmutableList<Path> systemIncludeRoots;

  public CxxPreprocessorInput(
      ImmutableSet<BuildTarget> rules,
      ImmutableList<String> cppflags,
      ImmutableList<String> cxxppflags,
      ImmutableMap<Path, SourcePath> includes,
      ImmutableList<Path> includeRoots,
      ImmutableList<Path> systemIncludeRoots) {
    this.rules = Preconditions.checkNotNull(rules);
    this.cppflags = Preconditions.checkNotNull(cppflags);
    this.cxxppflags = Preconditions.checkNotNull(cxxppflags);
    this.includes = Preconditions.checkNotNull(includes);
    this.includeRoots = Preconditions.checkNotNull(includeRoots);
    this.systemIncludeRoots = Preconditions.checkNotNull(systemIncludeRoots);
  }

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs) {
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();
    ImmutableList.Builder<String> cflags = ImmutableList.builder();
    ImmutableList.Builder<String> cxxflags = ImmutableList.builder();
    ImmutableMap.Builder<Path, SourcePath> includes = ImmutableMap.builder();
    ImmutableList.Builder<Path> includeRoots = ImmutableList.builder();
    ImmutableList.Builder<Path> systemIncludeRoots = ImmutableList.builder();

    for (CxxPreprocessorInput input : inputs) {
      rules.addAll(input.getRules());
      cflags.addAll(input.getCppflags());
      cxxflags.addAll(input.getCxxppflags());
      includes.putAll(input.getIncludes());
      includeRoots.addAll(input.getIncludeRoots());
      systemIncludeRoots.addAll(input.getSystemIncludeRoots());
    }

    return new CxxPreprocessorInput(
        rules.build(),
        cflags.build(),
        cxxflags.build(),
        includes.build(),
        includeRoots.build(),
        systemIncludeRoots.build());
  }

  public ImmutableSet<BuildTarget> getRules() {
    return rules;
  }

  public ImmutableList<String> getCppflags() {
    return cppflags;
  }

  public ImmutableList<String> getCxxppflags() {
    return cxxppflags;
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

    if (!cppflags.equals(that.cppflags)) {
      return false;
    }

    if (!cxxppflags.equals(that.cxxppflags)) {
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
        cppflags,
        cxxppflags,
        includes,
        includeRoots,
        systemIncludeRoots);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("rules", rules)
        .add("cppflags", cppflags)
        .add("cxxppflags", cxxppflags)
        .add("includes", includes)
        .add("includeRoots", includeRoots)
        .add("systemIncludeRoots", systemIncludeRoots)
        .toString();
  }

}
