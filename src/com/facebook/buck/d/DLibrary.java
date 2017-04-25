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

package com.facebook.buck.d;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class DLibrary extends NoopBuildRule implements NativeLinkable {

  private final BuildRuleResolver buildRuleResolver;
  private final DIncludes includes;

  public DLibrary(BuildRuleParams params, BuildRuleResolver buildRuleResolver, DIncludes includes) {
    super(params);
    this.buildRuleResolver = buildRuleResolver;
    this.includes = includes;
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps() {
    return ImmutableList.of();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps() {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    Archive archive =
        (Archive)
            buildRuleResolver.requireRule(
                getBuildTarget()
                    .withAppendedFlavors(
                        cxxPlatform.getFlavor(), CxxDescriptionEnhancer.STATIC_FLAVOR));
    return NativeLinkableInput.of(
        ImmutableList.of(archive.toArg()), ImmutableSet.of(), ImmutableSet.of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }

  public DIncludes getIncludes() throws NoSuchBuildTargetException {
    buildRuleResolver.requireRule(
        getBuildTarget().withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE));
    return includes;
  }
}
