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
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An action graph representation of a C/C++ library from the target graph, providing the
 * various interfaces to make it consumable by C/C++ preprocessing and native linkable rules.
 */
public class CxxLibrary extends AbstractCxxLibrary {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final boolean headerOnly;
  private final Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
      exportedPreprocessorFlags;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final Optional<String> supportedPlatformsRegex;
  private final ImmutableList<Path> frameworkSearchPaths;
  private final Linkage linkage;
  private final boolean linkWhole;
  private final Optional<String> soname;
  private final ImmutableSortedSet<BuildTarget> tests;

  public CxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      boolean headerOnly,
      Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
          exportedPreprocessorFlags,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags,
      Optional<String> supportedPlatformsRegex,
      ImmutableList<Path> frameworkSearchPaths,
      Linkage linkage,
      boolean linkWhole,
      Optional<String> soname,
      ImmutableSortedSet<BuildTarget> tests) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.headerOnly = headerOnly;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.frameworkSearchPaths = frameworkSearchPaths;
    this.linkage = linkage;
    this.linkWhole = linkWhole;
    this.soname = soname;
    this.tests = tests;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    BuildRule rule = CxxDescriptionEnhancer.requireBuildRule(
        params,
        ruleResolver,
        cxxPlatform.getFlavor(),
        CxxDescriptionEnhancer.getHeaderSymlinkTreeFlavor(headerVisibility));
    Preconditions.checkState(rule instanceof SymlinkTree);
    SymlinkTree symlinkTree = (SymlinkTree) rule;
    return CxxPreprocessorInput.builder()
        .addRules(symlinkTree.getBuildTarget())
        .putAllPreprocessorFlags(exportedPreprocessorFlags.apply(cxxPlatform))
        .setIncludes(
            CxxHeaders.builder()
                .putAllNameToPathMap(symlinkTree.getLinks())
                .putAllFullNameToPathMap(symlinkTree.getFullLinks())
                .build())
        .addIncludeRoots(symlinkTree.getRoot())
        .addAllFrameworkRoots(frameworkSearchPaths)
        .build();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    if (headerOnly) {
      return NativeLinkableInput.of(ImmutableSet.<SourcePath>of(), ImmutableList.<String>of());
    }

    // Build up the arguments used to link this library.  If we're linking the
    // whole archive, wrap the library argument in the necessary "ld" flags.
    final BuildRule libraryRule;
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(exportedLinkerFlags.apply(cxxPlatform));
    if (type == Linker.LinkableDepType.STATIC || linkage == Linkage.STATIC) {
      libraryRule = CxxDescriptionEnhancer.requireBuildRule(
          params,
          ruleResolver,
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
      Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
          getBuildTarget(),
          soname,
          cxxPlatform);
      libraryRule = CxxDescriptionEnhancer.requireBuildRule(
          params,
          ruleResolver,
          cxxPlatform.getFlavor(),
          CxxDescriptionEnhancer.SHARED_FLAVOR);
      linkerArgsBuilder.add(sharedLibraryPath.toString());
    }
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        ImmutableList.<SourcePath>of(
            new BuildTargetSourcePath(
                libraryRule.getProjectFilesystem(),
                libraryRule.getBuildTarget())),
        linkerArgs);
  }

  @Override
  public Optional<Linker.LinkableDepType> getPreferredLinkage(CxxPlatform cxxPlatform) {
    if (linkage == Linkage.STATIC) {
      return Optional.of(Linker.LinkableDepType.STATIC);
    }
    return Optional.absent();
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    ImmutableMap.Builder<Path, SourcePath> libs = ImmutableMap.builder();
    if (!headerOnly && linkage != Linkage.STATIC) {
      String sharedLibrarySoname =
          soname.or(CxxDescriptionEnhancer.getSharedLibrarySoname(getBuildTarget(), cxxPlatform));
      BuildRule sharedLibraryBuildRule = CxxDescriptionEnhancer.requireBuildRule(
          params,
          ruleResolver,
          cxxPlatform.getFlavor(),
          CxxDescriptionEnhancer.SHARED_FLAVOR);
      libs.put(
          Paths.get(sharedLibrarySoname),
          new BuildTargetSourcePath(
              sharedLibraryBuildRule.getProjectFilesystem(),
              sharedLibraryBuildRule.getBuildTarget()));
    }
    return PythonPackageComponents.of(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ libs.build(),
        /* zipSafe */ Optional.<Boolean>absent());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(params.getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addNativeLinkable(this);
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    if (headerOnly) {
      return ImmutableMap.of();
    }
    if (linkage == Linkage.STATIC) {
      return ImmutableMap.of();
    }
    if (supportedPlatformsRegex.isPresent() &&
        !CxxFlags.compilePlatformRegex(supportedPlatformsRegex.get())
            .matcher(cxxPlatform.getFlavor().toString())
            .find()) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    String sharedLibrarySoname =
        soname.or(CxxDescriptionEnhancer.getSharedLibrarySoname(getBuildTarget(), cxxPlatform));
    BuildRule sharedLibraryBuildRule = CxxDescriptionEnhancer.requireBuildRule(
        params,
        ruleResolver,
        cxxPlatform.getFlavor(),
        CxxDescriptionEnhancer.SHARED_FLAVOR);
    libs.put(
        sharedLibrarySoname,
        new BuildTargetSourcePath(
            sharedLibraryBuildRule.getProjectFilesystem(),
            sharedLibraryBuildRule.getBuildTarget()));
    return libs.build();
  }

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  public enum Linkage {
    ANY,
    STATIC,
  }

}
