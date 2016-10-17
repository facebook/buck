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
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;


@SuppressFieldNotInitialized
public class CxxConstructorArg extends AbstractDescriptionArg
    implements HasDefaultFlavors, HasTests {

  public ImmutableSortedSet<SourceWithFlags> srcs = ImmutableSortedSet.of();
  public PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs =
      PatternMatchedCollection.of();
  public SourceList headers = SourceList.EMPTY;
  public PatternMatchedCollection<SourceList> platformHeaders = PatternMatchedCollection.of();
  public Optional<SourcePath> prefixHeader;
  public ImmutableList<String> compilerFlags = ImmutableList.of();
  public ImmutableMap<CxxSource.Type, ImmutableList<String>> langCompilerFlags = ImmutableMap.of();
  public PatternMatchedCollection<ImmutableList<String>> platformCompilerFlags =
      PatternMatchedCollection.of();
  public ImmutableList<String> preprocessorFlags = ImmutableList.of();
  public PatternMatchedCollection<ImmutableList<String>> platformPreprocessorFlags =
      PatternMatchedCollection.of();
  public ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
      ImmutableMap.of();
  public ImmutableList<String> linkerFlags = ImmutableList.of();
  public PatternMatchedCollection<ImmutableList<String>> platformLinkerFlags =
      PatternMatchedCollection.of();
  public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
  public ImmutableSortedSet<FrameworkPath> libraries = ImmutableSortedSet.of();
  public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  public Optional<String> headerNamespace;
  public Optional<Linker.CxxRuntimeType> cxxRuntimeType;

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
}
