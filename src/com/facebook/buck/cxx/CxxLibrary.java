/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the
 * various interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibrary extends AbstractCxxLibrary {

  @AddToRuleKey
  private final boolean canBeAsset;

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Predicate<CxxPlatform> headerOnly;
  private final Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
      exportedPreprocessorFlags;
  private final Function<? super CxxPlatform,
      Pair<ImmutableList<String>, ImmutableSet<SourcePath>>> exportedLinkerFlags;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final Linkage linkage;
  private final boolean linkWhole;
  private final Optional<String> soname;
  private final ImmutableSortedSet<BuildTarget> tests;

  private final Map<Pair<Flavor, HeaderVisibility>, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      cxxPreprocessorInputCache = Maps.newHashMap();

  public CxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      Predicate<CxxPlatform> headerOnly,
      Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
          exportedPreprocessorFlags,
      Function<? super CxxPlatform,
          Pair<ImmutableList<String>, ImmutableSet<SourcePath>>> exportedLinkerFlags,
      Optional<Pattern> supportedPlatformsRegex,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Linkage linkage,
      boolean linkWhole,
      Optional<String> soname,
      ImmutableSortedSet<BuildTarget> tests,
      boolean canBeAsset) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.headerOnly = headerOnly;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.linkage = linkage;
    this.linkWhole = linkWhole;
    this.soname = soname;
    this.tests = tests;
    this.canBeAsset = canBeAsset;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent() ||
        supportedPlatformsRegex.get()
            .matcher(cxxPlatform.getFlavor().toString())
            .find();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    return CxxPreprocessables.getCxxPreprocessorInput(
        targetGraph,
        params,
        ruleResolver,
        cxxPlatform.getFlavor(),
        headerVisibility,
        CxxPreprocessables.IncludeType.LOCAL,
        exportedPreprocessorFlags.apply(cxxPlatform),
        cxxPlatform,
        frameworks);
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    Pair<Flavor, HeaderVisibility> key = new Pair<>(cxxPlatform.getFlavor(), headerVisibility);
    ImmutableMap<BuildTarget, CxxPreprocessorInput> result = cxxPreprocessorInputCache.get(key);
    if (result == null) {
      Map<BuildTarget, CxxPreprocessorInput> builder = Maps.newLinkedHashMap();
      builder.put(
          getBuildTarget(),
          getCxxPreprocessorInput(targetGraph, cxxPlatform, headerVisibility));
      for (BuildRule dep : getDeps()) {
        if (dep instanceof CxxPreprocessorDep) {
          builder.putAll(
              ((CxxPreprocessorDep) dep).getTransitiveCxxPreprocessorInput(
                  targetGraph,
                  cxxPlatform,
                  headerVisibility));
        }
      }
      result = ImmutableMap.copyOf(builder);
      cxxPreprocessorInputCache.put(key, result);
    }
    return result;
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }

    if (headerOnly.apply(cxxPlatform)) {
      return NativeLinkableInput.of(
          ImmutableList.<SourcePath>of(),
          ImmutableList.<String>of(),
          Preconditions.checkNotNull(frameworks),
          ImmutableSet.<FrameworkPath>of());
    }

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    final Pair<ImmutableList<String>, ImmutableSet<SourcePath>> flagsAndBuildInputs =
        exportedLinkerFlags.apply(cxxPlatform);
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(flagsAndBuildInputs.getFirst());

    final BuildRule libraryRule;
    if (type != Linker.LinkableDepType.SHARED || linkage == Linkage.STATIC) {
      libraryRule = requireBuildRule(
          targetGraph,
          cxxPlatform.getFlavor(),
          type == Linker.LinkableDepType.STATIC ?
              CxxDescriptionEnhancer.STATIC_FLAVOR :
              CxxDescriptionEnhancer.STATIC_PIC_FLAVOR);
      Path staticLibraryPath =
          CxxDescriptionEnhancer.getStaticLibraryPath(
              getBuildTarget(),
              cxxPlatform.getFlavor(),
              type == Linker.LinkableDepType.STATIC ?
                  CxxSourceRuleFactory.PicType.PDC :
                  CxxSourceRuleFactory.PicType.PIC);
      if (linkWhole) {
        Linker linker = cxxPlatform.getLd();
        linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
      } else {
        linkerArgsBuilder.add(staticLibraryPath.toString());
      }
    } else {
      String sharedLibrarySoname =
          soname.or(
              CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                  params.getBuildTarget(), cxxPlatform));
      Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
          getBuildTarget(),
          sharedLibrarySoname,
          cxxPlatform);
      libraryRule = requireBuildRule(
          targetGraph,
          cxxPlatform.getFlavor(),
          CxxDescriptionEnhancer.SHARED_FLAVOR);
      linkerArgsBuilder.add(sharedLibraryPath.toString());
    }
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        ImmutableList.<SourcePath>builder()
            .add(new BuildTargetSourcePath(libraryRule.getBuildTarget()))
            .addAll(flagsAndBuildInputs.getSecond())
            .build(),
        linkerArgs,
        Preconditions.checkNotNull(frameworks),
        Preconditions.checkNotNull(libraries));
  }

  public BuildRule requireBuildRule(
      TargetGraph targetGraph,
      Flavor ... flavors) {
    return CxxDescriptionEnhancer.requireBuildRule(targetGraph, params, ruleResolver, flavors);
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return linkage;
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    if (headerOnly.apply(cxxPlatform)) {
      return PythonPackageComponents.of();
    }
    if (linkage == Linkage.STATIC) {
      return PythonPackageComponents.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return PythonPackageComponents.of();
    }
    ImmutableMap.Builder<Path, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                getBuildTarget(),
                cxxPlatform));
    BuildRule sharedLibraryBuildRule = requireBuildRule(
        targetGraph,
        cxxPlatform.getFlavor(),
        CxxDescriptionEnhancer.SHARED_FLAVOR);
    libs.put(
        Paths.get(sharedLibrarySoname),
        new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
    return PythonPackageComponents.of(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ libs.build(),
        /* prebuiltLibraries */ ImmutableSet.<SourcePath>of(),
        /* zipSafe */ Optional.<Boolean>absent());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(params.getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (canBeAsset) {
      collector.addNativeLinkableAsset(this);
    } else {
      collector.addNativeLinkable(this);
    }
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    if (headerOnly.apply(cxxPlatform)) {
      return ImmutableMap.of();
    }
    if (linkage == Linkage.STATIC) {
      return ImmutableMap.of();
    }
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                getBuildTarget(),
                cxxPlatform));
    BuildRule sharedLibraryBuildRule = requireBuildRule(
        targetGraph,
        cxxPlatform.getFlavor(),
        CxxDescriptionEnhancer.SHARED_FLAVOR);
    libs.put(
        sharedLibrarySoname,
        new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
    return libs.build();
  }

  @Override
  public boolean isTestedBy(BuildTarget testTarget) {
    return tests.contains(testTarget);
  }

}
