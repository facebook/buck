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
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasDefaultFlavors;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;


@SuppressFieldNotInitialized
public class CxxConstructorArg extends AbstractDescriptionArg
    implements HasDefaultFlavors, HasTests, HasSystemFrameworkAndLibraries {

  public ImmutableSortedSet<SourceWithFlags> srcs = ImmutableSortedSet.of();
  public PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs =
      PatternMatchedCollection.of();
  public SourceList headers = SourceList.EMPTY;
  public PatternMatchedCollection<SourceList> platformHeaders = PatternMatchedCollection.of();
  public Optional<SourcePath> prefixHeader;
  public Optional<SourcePath> precompiledHeader = Optional.empty();
  public ImmutableList<String> compilerFlags = ImmutableList.of();
  public ImmutableMap<CxxSource.Type, ImmutableList<String>> langCompilerFlags = ImmutableMap.of();
  public PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags =
      PatternMatchedCollection.of();
  public ImmutableList<String> preprocessorFlags = ImmutableList.of();
  public PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags =
      PatternMatchedCollection.of();
  public ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
      ImmutableMap.of();
  public ImmutableList<StringWithMacros> linkerFlags = ImmutableList.of();
  public PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags =
      PatternMatchedCollection.of();
  public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
  public ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of();
  public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  public PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps =
      PatternMatchedCollection.of();
  public Optional<String> headerNamespace;
  public Optional<Linker.CxxRuntimeType> cxxRuntimeType;
  public ImmutableList<String> includeDirs = ImmutableList.of();

  @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  public ImmutableMap<String, Flavor> defaults = ImmutableMap.of();

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  @Override
  public ImmutableSortedSet<Flavor> getDefaultFlavors() {
    // We don't (yet) use the keys in the default_flavors map, but we
    // plan to eventually support key-value flavors.
    return ImmutableSortedSet.copyOf(defaults.values());
  }

  @Override
  public ImmutableSortedSet<FrameworkPath> getFrameworks() {
    return frameworks;
  }

  @Override
  public ImmutableSortedSet<FrameworkPath> getLibraries() {
    return libraries;
  }

  /**
   * @return the C/C++ deps this rule builds against.
   */
  public CxxDeps getCxxDeps() {
    return CxxDeps.builder()
        .addDeps(deps)
        .addPlatformDeps(platformDeps)
        .addDep(precompiledHeader)
        .build();
  }

}
