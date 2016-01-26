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

package com.facebook.buck.halide;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.Map;

public class HalideLibrary
    extends NoopBuildRule
    implements CxxPreprocessorDep, NativeLinkable {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Map<
      Pair<Flavor, HeaderVisibility>,
      ImmutableMap<BuildTarget, CxxPreprocessorInput>> cxxPreprocessorInputCache =
      Maps.newHashMap();

  protected HalideLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    switch (headerVisibility) {
      case PUBLIC:
        return CxxPreprocessables.getCxxPreprocessorInput(
            params,
            ruleResolver,
            cxxPlatform.getFlavor(),
            headerVisibility,
            CxxPreprocessables.IncludeType.SYSTEM,
            ImmutableMultimap.<CxxSource.Type, String>of(),
            ImmutableList.<FrameworkPath>of());
      case PRIVATE:
        return CxxPreprocessorInput.EMPTY;
    }

    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    Pair<Flavor, HeaderVisibility> key = new Pair<>(
        cxxPlatform.getFlavor(),
        headerVisibility);
    ImmutableMap<BuildTarget, CxxPreprocessorInput> result =
        cxxPreprocessorInputCache.get(key);
    if (result == null) {
      ImmutableMap.Builder<BuildTarget, CxxPreprocessorInput> builder =
          ImmutableMap.builder();
      builder.put(
          getBuildTarget(),
          getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      result = builder.build();
      cxxPreprocessorInputCache.put(key, result);
    }
    return result;
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    return ImmutableList.of();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    return NativeLinkableInput.of(
        SourcePathArg.from(
            getResolver(),
            new BuildTargetSourcePath(
                ruleResolver
                    .requireRule(
                        getBuildTarget().withFlavors(
                            CxxDescriptionEnhancer.flavorForLinkableDepType(type),
                            cxxPlatform.getFlavor()))
                    .getBuildTarget())),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return NativeLinkable.Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

}
