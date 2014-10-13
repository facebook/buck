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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("prebuilt_cxx_library");

  private static final Flavor SHARED = new Flavor("shared");

  private final CxxPlatform cxxPlatform;

  public PrebuiltCxxLibraryDescription(CxxPlatform cxxPlatform) {
    this.cxxPlatform = Preconditions.checkNotNull(cxxPlatform);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> CxxLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    final BuildTarget target = params.getBuildTarget();

    final boolean headerOnly = args.headerOnly.or(false);
    final boolean provided = args.provided.or(false);
    final boolean linkWhole = args.linkWhole.or(false);
    final String libDir = args.libDir.or("lib");
    final String libName = args.libName.or(target.getShortNameOnly());
    final String soname = args.soname.or(String.format("lib%s.so", libName));

    final Path staticLibraryPath =
        target.getBasePath()
            .resolve(libDir)
            .resolve(String.format("lib%s.a", libName));
    final SourcePath staticLibrary = new PathSourcePath(staticLibraryPath);

    // If a shared library doesn't exist for this prebuilt C/C++ library, we attempt to build
    // one out of the static lib, which we assume is compiled using PIC.
    final Path sharedLibraryPath;
    final SourcePath sharedLibrary;
    Path prebuiltSharedLibraryPath =
        target.getBasePath()
            .resolve(libDir)
            .resolve(String.format("lib%s.so", libName));
    if (headerOnly || params.getProjectFilesystem().exists(prebuiltSharedLibraryPath)) {
      sharedLibraryPath = prebuiltSharedLibraryPath;
      sharedLibrary = new PathSourcePath(sharedLibraryPath);
    } else {
      BuildTarget sharedLibraryTarget = BuildTargets.extendFlavoredBuildTarget(
          params.getBuildTarget(),
          SHARED);
      sharedLibraryPath = BuildTargets.getBinPath(sharedLibraryTarget, "%s").resolve(soname);
      CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
          cxxPlatform,
          params,
          pathResolver,
          ImmutableList.<String>of(),
          ImmutableList.<String>of(),
          BuildTargets.extendFlavoredBuildTarget(
              params.getBuildTarget(),
              SHARED),
          CxxLinkableEnhancer.LinkType.SHARED,
          Optional.of(soname),
          sharedLibraryPath,
          ImmutableList.of(staticLibrary),
          NativeLinkable.Type.SHARED,
          params.getDeps());
      resolver.addToIndex(cxxLink);
      sharedLibrary = new BuildTargetSourcePath(cxxLink.getBuildTarget());
    }

    Function<String, Path> fullPathFn = new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return target.getBasePath().resolve(input);
      }
    };

    // Resolve all the target-base-path-relative include paths to their full paths.
    final ImmutableList<Path> includeDirs = FluentIterable
        .from(args.includeDirs.or(ImmutableList.of("include")))
        .transform(fullPathFn)
        .toList();

    return new CxxLibrary(params, pathResolver) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return CxxPreprocessorInput.builder()
            // Just pass the include dirs as system includes.
          .setSystemIncludeRoots(includeDirs)
          .build();
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Linker linker, Type type) {

        // Build the library path and linker arguments that we pass through the
        // {@link NativeLinkable} interface for linking.
        ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
        ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
        if (!headerOnly) {
          if (provided || type == Type.SHARED) {
            librariesBuilder.add(sharedLibrary);
            linkerArgsBuilder.add(sharedLibraryPath.toString());
          } else {
            librariesBuilder.add(staticLibrary);
            if (linkWhole) {
              linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
            } else {
              linkerArgsBuilder.add(staticLibraryPath.toString());
            }
          }
        }
        final ImmutableList<SourcePath> libraries = librariesBuilder.build();
        final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

        return new NativeLinkableInput(
            /* inputs */ libraries,
            /* args */ linkerArgs);
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {

        // Build up the shared library list to contribute to a python executable package.
        ImmutableMap.Builder<Path, SourcePath> nativeLibrariesBuilder = ImmutableMap.builder();
        if (!headerOnly && !provided) {
          nativeLibrariesBuilder.put(
              Paths.get(soname),
              sharedLibrary);
        }
        ImmutableMap<Path, SourcePath> nativeLibraries = nativeLibrariesBuilder.build();

        return new PythonPackageComponents(
            /* modules */ ImmutableMap.<Path, SourcePath>of(),
            /* resources */ ImmutableMap.<Path, SourcePath>of(),
            nativeLibraries);
      }

    };
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableList<String>> includeDirs;
    public Optional<String> libName;
    public Optional<String> libDir;
    public Optional<Boolean> headerOnly;
    public Optional<Boolean> provided;
    public Optional<Boolean> linkWhole;
    public Optional<String> soname;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
