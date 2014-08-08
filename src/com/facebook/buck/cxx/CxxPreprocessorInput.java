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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
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

  // Normal include directories where headers are found.
  private final ImmutableList<Path> includes;

  // Include directories where system headers.
  private final ImmutableList<Path> systemIncludes;

  public CxxPreprocessorInput(
      ImmutableSet<BuildTarget> rules,
      ImmutableList<String> cppflags,
      ImmutableList<String> cxxppflags,
      ImmutableList<Path> includes,
      ImmutableList<Path> systemIncludes) {
    this.rules = Preconditions.checkNotNull(rules);
    this.cppflags = Preconditions.checkNotNull(cppflags);
    this.cxxppflags = Preconditions.checkNotNull(cxxppflags);
    this.includes = Preconditions.checkNotNull(includes);
    this.systemIncludes = Preconditions.checkNotNull(systemIncludes);
  }

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs) {
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();
    ImmutableList.Builder<String> cflags = ImmutableList.builder();
    ImmutableList.Builder<String> cxxflags = ImmutableList.builder();
    ImmutableList.Builder<Path> includes = ImmutableList.builder();
    ImmutableList.Builder<Path> systemIncludes = ImmutableList.builder();

    for (CxxPreprocessorInput input : inputs) {
      rules.addAll(input.getRules());
      cflags.addAll(input.getCppflags());
      cxxflags.addAll(input.getCxxppflags());
      includes.addAll(input.getIncludes());
      systemIncludes.addAll(input.getSystemIncludes());
    }

    return new CxxPreprocessorInput(
        rules.build(),
        cflags.build(),
        cxxflags.build(),
        includes.build(),
        systemIncludes.build());
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

  public ImmutableList<Path> getIncludes() {
    return includes;
  }

  public ImmutableList<Path> getSystemIncludes() {
    return systemIncludes;
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

    if (!systemIncludes.equals(that.systemIncludes)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(rules, cppflags, cxxppflags, includes, systemIncludes);
  }

}
