/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class HaskellLibrary extends NoopBuildRule implements HaskellCompileDep, NativeLinkable {

  private final BuildRuleResolver ruleResolver;

  public HaskellLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRuleResolver ruleResolver) {
    super(params, resolver);
    this.ruleResolver = ruleResolver;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeps())
        .filter(NativeLinkable.class);
  }

  private BuildRule requireBuildRule(Flavor... flavors) throws NoSuchBuildTargetException {
    BuildTarget requiredBuildTarget =
        BuildTarget.builder(getBuildTarget())
            .addFlavors(flavors)
            .build();
    return ruleResolver.requireRule(requiredBuildTarget);
  }

  private HaskellLibraryDescription.Type getLibraryType(Linker.LinkableDepType type) {
    switch (type) {
      case SHARED:
        return HaskellLibraryDescription.Type.SHARED;
      case STATIC:
        return HaskellLibraryDescription.Type.STATIC;
      case STATIC_PIC:
        return HaskellLibraryDescription.Type.STATIC_PIC;
    }
    throw new AssertionError();
  }

  private HaskellLibraryDescription.Type getPackageType(Linker.LinkableDepType depType) {
    switch (depType) {
      case SHARED:
        return HaskellLibraryDescription.Type.PACKAGE_SHARED;
      case STATIC:
        return HaskellLibraryDescription.Type.PACKAGE_STATIC;
      case STATIC_PIC:
        return HaskellLibraryDescription.Type.PACKAGE_STATIC_PIC;
    }
    throw new AssertionError();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type)
      throws NoSuchBuildTargetException {
    BuildRule rule = requireBuildRule(cxxPlatform.getFlavor(), getLibraryType(type).getFlavor());
    return NativeLinkableInput.of(
        ImmutableList.<Arg>of(
            new SourcePathArg(getResolver(), new BuildTargetSourcePath(rule.getBuildTarget()))),
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            Optional.<String>absent(),
            getBuildTarget(),
            cxxPlatform);
    BuildRule sharedLibraryBuildRule =
        requireBuildRule(
            cxxPlatform.getFlavor(),
            CxxDescriptionEnhancer.SHARED_FLAVOR);
    libs.put(
        sharedLibrarySoname,
        new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
    return libs.build();
  }

  @VisibleForTesting
  protected HaskellPackageRule requirePackageRule(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    return (HaskellPackageRule) requireBuildRule(
        cxxPlatform.getFlavor(),
        getPackageType(depType).getFlavor());
  }

  @Override
  public HaskellCompileInput getCompileInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    HaskellPackageRule rule = requirePackageRule(cxxPlatform, depType);
    return HaskellCompileInput.builder()
        .addPackages(rule.getPackage())
        .build();
  }

}
