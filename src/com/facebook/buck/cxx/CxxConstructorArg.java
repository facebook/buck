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
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public interface CxxConstructorArg
    extends CommonDescriptionArg,
        HasDeclaredDeps,
        HasDefaultFlavors,
        HasTests,
        HasSystemFrameworkAndLibraries {
  @Value.NaturalOrder
  ImmutableSortedSet<SourceWithFlags> getSrcs();

  @Value.Default
  default PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> getPlatformSrcs() {
    return PatternMatchedCollection.of();
  }

  @Value.Default
  default SourceList getHeaders() {
    return SourceList.EMPTY;
  }

  @Value.Default
  default PatternMatchedCollection<SourceList> getPlatformHeaders() {
    return PatternMatchedCollection.of();
  }

  Optional<SourcePath> getPrefixHeader();

  Optional<SourcePath> getPrecompiledHeader();

  ImmutableList<String> getCompilerFlags();

  ImmutableMap<CxxSource.Type, ImmutableList<String>> getLangCompilerFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<String>> getPlatformCompilerFlags() {
    return PatternMatchedCollection.of();
  }

  ImmutableList<String> getPreprocessorFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<String>> getPlatformPreprocessorFlags() {
    return PatternMatchedCollection.of();
  }

  ImmutableMap<CxxSource.Type, ImmutableList<String>> getLangPreprocessorFlags();

  ImmutableList<StringWithMacros> getLinkerFlags();

  @Value.Default
  default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformLinkerFlags() {
    return PatternMatchedCollection.of();
  }

  @Value.Default
  default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
    return PatternMatchedCollection.of();
  }

  Optional<String> getHeaderNamespace();

  Optional<Linker.CxxRuntimeType> getCxxRuntimeType();

  ImmutableList<String> getIncludeDirs();

  ImmutableMap<String, Flavor> getDefaults();

  @Override
  @Value.Derived
  default ImmutableSortedSet<Flavor> getDefaultFlavors() {
    // We don't (yet) use the keys in the default_flavors map, but we
    // plan to eventually support key-value flavors.
    return ImmutableSortedSet.copyOf(getDefaults().values());
  }

  /** @return the C/C++ deps this rule builds against. */
  @Value.Derived
  default CxxDeps getCxxDeps() {
    return getPrivateCxxDeps();
  }

  /** @return C/C++ deps which are *not* propagated to dependents. */
  @Value.Derived
  default CxxDeps getPrivateCxxDeps() {
    return CxxDeps.builder()
        .addDeps(getDeps())
        .addPlatformDeps(getPlatformDeps())
        .addDep(getPrecompiledHeader())
        .build();
  }
}
