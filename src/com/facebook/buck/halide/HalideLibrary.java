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

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class HalideLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements CxxPreprocessorDep, NativeLinkable {

  private final ActionGraphBuilder graphBuilder;
  private final Optional<Pattern> supportedPlatformsRegex;

  private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
      new TransitiveCxxPreprocessorInputCache(this);

  protected HalideLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      Optional<Pattern> supportedPlatformsRegex) {
    super(buildTarget, projectFilesystem, params);
    this.graphBuilder = graphBuilder;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return CxxPreprocessorInput.of();
    }
    return CxxPreprocessables.getCxxPreprocessorInput(
        getBuildTarget(),
        graphBuilder,
        /* hasHeaderSymlinkTree */ true,
        cxxPlatform,
        HeaderVisibility.PUBLIC,
        CxxPreprocessables.IncludeType.SYSTEM,
        ImmutableMultimap.of(),
        ImmutableList.of());
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(
      CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return getNativeLinkableDeps(ruleResolver);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps(BuildRuleResolver ruleResolver) {
    return ImmutableList.of();
  }

  private Arg requireLibraryArg(CxxPlatform cxxPlatform, Linker.LinkableDepType type) {
    BuildRule rule =
        graphBuilder.requireRule(
            getBuildTarget()
                .withFlavors(
                    CxxDescriptionEnhancer.flavorForLinkableDepType(type),
                    cxxPlatform.getFlavor()));
    if (rule instanceof Archive) {
      return ((Archive) rule).toArg();
    } else {
      return SourcePathArg.of(Preconditions.checkNotNull(rule.getSourcePathToOutput()));
    }
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions,
      ActionGraphBuilder graphBuilder) {
    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }
    return NativeLinkableInput.of(
        ImmutableList.of(requireLibraryArg(cxxPlatform, type)),
        ImmutableSet.of(),
        ImmutableSet.of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return NativeLinkable.Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return ImmutableMap.of();
  }
}
