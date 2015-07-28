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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class PrebuiltCxxLibrary extends AbstractCxxLibrary {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final ImmutableList<Path> includeDirs;
  private final Optional<String> libDir;
  private final Optional<String> libName;
  private final Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
      exportedPreprocessorFlags;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final Optional<String> soname;
  private final boolean headerOnly;
  private final boolean linkWhole;
  private final boolean provided;


  private final Map<Pair<Flavor, HeaderVisibility>, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      cxxPreprocessorInputCache = Maps.newHashMap();

  public PrebuiltCxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableList<Path> includeDirs,
      Optional<String> libDir,
      Optional<String> libName,
      Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
          exportedPreprocessorFlags,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags,
      Optional<String> soname,
      boolean headerOnly,
      boolean linkWhole,
      boolean provided) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.pathResolver = pathResolver;
    this.includeDirs = includeDirs;
    this.libDir = libDir;
    this.libName = libName;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.soname = soname;
    this.headerOnly = headerOnly;
    this.linkWhole = linkWhole;
    this.provided = provided;
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action
   * graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(TargetGraph targetGraph, CxxPlatform cxxPlatform) {
    Path sharedLibraryPath =
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);

    // If the shared library is prebuilt, just return a reference to it.
    if (params.getProjectFilesystem().exists(sharedLibraryPath)) {
      return new PathSourcePath(params.getProjectFilesystem(), sharedLibraryPath);
    }

    // Otherwise, generate it's build rule.
    BuildRule sharedLibrary =
        CxxDescriptionEnhancer.requireBuildRule(
            targetGraph,
            params,
            ruleResolver,
            cxxPlatform.getFlavor(),
            CxxDescriptionEnhancer.SHARED_FLAVOR);

    return new BuildTargetSourcePath(sharedLibrary.getBuildTarget());
  }

  /**
   * @return the {@link Path} representing the actual static PIC library.
   */
  private Path getStaticPicLibrary(CxxPlatform cxxPlatform) {
    Path staticPicLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticPicLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);

    // If a specific static-pic variant isn't available, then just use the static variant.
    if (!params.getProjectFilesystem().exists(staticPicLibraryPath)) {
      staticPicLibraryPath =
          PrebuiltCxxLibraryDescription.getStaticLibraryPath(
              getBuildTarget(),
              cxxPlatform,
              libDir,
              libName);
    }

    return staticPicLibraryPath;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return CxxPreprocessorInput.builder()
            .from(
                CxxPreprocessables.getCxxPreprocessorInput(
                    targetGraph,
                    params,
                    ruleResolver,
                    cxxPlatform.getFlavor(),
                    headerVisibility,
                    CxxPreprocessables.IncludeType.SYSTEM,
                    exportedPreprocessorFlags.apply(cxxPlatform),
                    /* frameworkSearchPaths */ ImmutableList.<Path>of()))
            // Just pass the include dirs as system includes.
            .addAllSystemIncludeRoots(ImmutableSortedSet.copyOf(includeDirs))
            .build();
      case PRIVATE:
        return CxxPreprocessorInput.EMPTY;
    }

    // We explicitly don't put this in a default statement because we
    // want the compiler to warn if someone modifies the HeaderVisibility enum.
    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
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
    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform)));
    if (!headerOnly) {
      if (provided || type == Linker.LinkableDepType.SHARED) {
        SourcePath sharedLibrary = requireSharedLibrary(targetGraph, cxxPlatform);
        librariesBuilder.add(sharedLibrary);
        linkerArgsBuilder.add(pathResolver.getPath(sharedLibrary).toString());
      } else {
        Path staticLibraryPath =
            type == Linker.LinkableDepType.STATIC_PIC ?
                getStaticPicLibrary(cxxPlatform) :
                PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                    getBuildTarget(),
                    cxxPlatform,
                    libDir,
                    libName);
        librariesBuilder.add(new PathSourcePath(getProjectFilesystem(), staticLibraryPath));
        if (linkWhole) {
          Linker linker = cxxPlatform.getLd();
          linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
        } else {
          linkerArgsBuilder.add(staticLibraryPath.toString());
        }
      }
    }
    final ImmutableList<SourcePath> libraries = librariesBuilder.build();
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(libraries, linkerArgs, ImmutableSet.<Path>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    String resolvedSoname =
        PrebuiltCxxLibraryDescription.getSoname(getBuildTarget(), cxxPlatform, soname, libName);

    // Build up the shared library list to contribute to a python executable package.
    ImmutableMap.Builder<Path, SourcePath> nativeLibrariesBuilder = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary(targetGraph, cxxPlatform);
      nativeLibrariesBuilder.put(
          Paths.get(resolvedSoname),
          sharedLibrary);
    }
    ImmutableMap<Path, SourcePath> nativeLibraries = nativeLibrariesBuilder.build();

    return PythonPackageComponents.of(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        nativeLibraries,
        /* prebuiltLibraries */ ImmutableSet.<SourcePath>of(),
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
  public ImmutableMap<String, SourcePath> getSharedLibraries(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform) {
    String resolvedSoname =
        PrebuiltCxxLibraryDescription.getSoname(getBuildTarget(), cxxPlatform, soname, libName);
    ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary(targetGraph, cxxPlatform);
      solibs.put(resolvedSoname, sharedLibrary);
    }
    return solibs.build();
  }

  @Override
  public boolean isTestedBy(BuildTarget buildTarget) {
    return false;
  }
}
