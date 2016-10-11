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
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class PrebuiltCxxLibrary
    extends NoopBuildRule
    implements AbstractCxxLibrary, CanProvideNativeLinkTarget {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Iterable<? extends NativeLinkable> exportedDeps;
  private final ImmutableList<String> includeDirs;
  private final Optional<String> libDir;
  private final Optional<String> libName;
  private final Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
      exportedPreprocessorFlags;
  private final Function<CxxPlatform, Boolean> hasHeaders;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final Optional<String> soname;
  private final boolean linkWithoutSoname;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final boolean forceStatic;
  private final boolean headerOnly;
  private final boolean linkWhole;
  private final boolean provided;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final boolean canBeAsset;

  private final Map<Pair<Flavor, Linker.LinkableDepType>, NativeLinkableInput> nativeLinkableCache =
      new HashMap<>();

  private final LoadingCache<
          CxxPreprocessables.CxxPreprocessorInputCacheKey,
          ImmutableMap<BuildTarget, CxxPreprocessorInput>
        > transitiveCxxPreprocessorInputCache =
      CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  public PrebuiltCxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      Iterable<? extends NativeLinkable> exportedDeps,
      ImmutableList<String> includeDirs,
      Optional<String> libDir,
      Optional<String> libName,
      Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
          exportedPreprocessorFlags,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags,
      Optional<String> soname,
      boolean linkWithoutSoname,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      boolean forceStatic,
      boolean headerOnly,
      boolean linkWhole,
      boolean provided,
      Function<CxxPlatform, Boolean> hasHeaders,
      Optional<Pattern> supportedPlatformsRegex,
      boolean canBeAsset) {
    super(params, pathResolver);
    this.canBeAsset = canBeAsset;
    Preconditions.checkArgument(!forceStatic || !provided);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.exportedDeps = exportedDeps;
    this.includeDirs = includeDirs;
    this.libDir = libDir;
    this.libName = libName;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.hasHeaders = hasHeaders;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.soname = soname;
    this.linkWithoutSoname = linkWithoutSoname;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.forceStatic = forceStatic;
    this.headerOnly = headerOnly;
    this.linkWhole = linkWhole;
    this.provided = provided;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
  }

  protected String getSoname(CxxPlatform cxxPlatform) {
    return PrebuiltCxxLibraryDescription.getSoname(
        getBuildTarget(),
        params.getCellRoots(),
        ruleResolver,
        cxxPlatform,
        soname,
        libName);
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent() ||
        supportedPlatformsRegex.get()
            .matcher(cxxPlatform.getFlavor().toString())
            .find();
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action
   * graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    SourcePath sharedLibraryPath =
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            getBuildTarget(),
            params.getCellRoots(),
            getProjectFilesystem(),
            ruleResolver,
            cxxPlatform,
            libDir,
            libName);

    // If the shared library is prebuilt, just return a reference to it.
    // TODO(alisdair04): this code misbehaves. whether the file exists should have been figured out
    // earlier during parsing/target graph creation, or it should be later when steps being
    // produced. This is preventing distributed build loading files lazily.
    if (sharedLibraryPath instanceof BuildTargetSourcePath ||
        params.getProjectFilesystem().exists(getResolver().getAbsolutePath(sharedLibraryPath))) {
      return sharedLibraryPath;
    }

    // Otherwise, generate it's build rule.
    BuildRule sharedLibrary =
        ruleResolver.requireRule(
            getBuildTarget().withFlavors(
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR));

    return new BuildTargetSourcePath(sharedLibrary.getBuildTarget());
  }

  /**
   * @return the {@link Optional} containing a {@link SourcePath} representing the actual
   * static PIC library.
   */
  private Optional<SourcePath> getStaticPicLibrary(CxxPlatform cxxPlatform) {
    SourcePath staticPicLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticPicLibraryPath(
            getBuildTarget(),
            params.getCellRoots(),
            params.getProjectFilesystem(),
            ruleResolver,
            cxxPlatform,
            libDir,
            libName);
    if (params.getProjectFilesystem().exists(getResolver().getAbsolutePath(staticPicLibraryPath))) {
      return Optional.of(staticPicLibraryPath);
    }

    // If a specific static-pic variant isn't available, then just use the static variant.
    SourcePath staticLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            getBuildTarget(),
            params.getCellRoots(),
            getProjectFilesystem(),
            ruleResolver,
            cxxPlatform,
            libDir,
            libName);
    if (params.getProjectFilesystem().exists(getResolver().getAbsolutePath(staticLibraryPath))) {
      return Optional.of(staticLibraryPath);
    }

    return Optional.absent();
  }

  @Override
  public Iterable<? extends CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getDeps())
        .filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      final CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

    switch (headerVisibility) {
      case PUBLIC:
        if (Preconditions.checkNotNull(hasHeaders.apply(cxxPlatform))) {
          CxxPreprocessables.addHeaderSymlinkTree(
              builder,
              getBuildTarget(),
              ruleResolver,
              cxxPlatform,
              headerVisibility,
              CxxPreprocessables.IncludeType.SYSTEM);
        }
        builder.putAllPreprocessorFlags(
            Preconditions.checkNotNull(exportedPreprocessorFlags.apply(cxxPlatform)));
        builder.addAllFrameworks(frameworks);
        final Iterable<SourcePath> includePaths = Iterables.transform(
            includeDirs,
            input -> PrebuiltCxxLibraryDescription.getApplicableSourcePath(
                params.getBuildTarget(),
                params.getCellRoots(),
                params.getProjectFilesystem(),
                ruleResolver,
                cxxPlatform,
                input,
                Optional.absent()
            ));
        for (SourcePath includePath : includePaths) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includePath));
        }
        return builder.build();
      case PRIVATE:
        return builder.build();
    }

    // We explicitly don't put this in a default statement because we
    // want the compiler to warn if someone modifies the HeaderVisibility enum.
    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public Optional<HeaderSymlinkTree> getExportedHeaderSymlinkTree(CxxPlatform cxxPlatform) {
    if (hasHeaders.apply(cxxPlatform)) {
      return Optional.of(
          CxxPreprocessables.requireHeaderSymlinkTreeForLibraryTarget(
              ruleResolver,
              getBuildTarget(),
              cxxPlatform.getFlavor()));
    } else {
      return Optional.absent();
    }
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    return transitiveCxxPreprocessorInputCache.getUnchecked(
        ImmutableCxxPreprocessorInputCacheKey.of(cxxPlatform, headerVisibility));
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return exportedDeps;
  }

  public NativeLinkableInput getNativeLinkableInputUncached(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }

    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        StringArg.from(Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform))));

    if (!headerOnly) {
      if (type == Linker.LinkableDepType.SHARED) {
        Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.STATIC);
        final SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
        if (linkWithoutSoname) {
          if (!(sharedLibrary instanceof PathSourcePath)) {
            throw new HumanReadableException(
                "%s: can only link prebuilt DSOs without sonames",
                getBuildTarget());
          }
          linkerArgsBuilder.add(new RelativeLinkArg((PathSourcePath) sharedLibrary));
        } else {
          linkerArgsBuilder.add(
              new SourcePathArg(getResolver(), requireSharedLibrary(cxxPlatform)));
        }
      } else {
        Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.SHARED);
        SourcePath staticLibraryPath =
            type == Linker.LinkableDepType.STATIC_PIC ?
                getStaticPicLibrary(cxxPlatform).get() :
                PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                    getBuildTarget(),
                    params.getCellRoots(),
                    params.getProjectFilesystem(),
                    ruleResolver,
                    cxxPlatform,
                    libDir,
                    libName);
        SourcePathArg staticLibrary =
            new SourcePathArg(
                getResolver(),
                staticLibraryPath);
        if (linkWhole) {
          Linker linker = cxxPlatform.getLd().resolve(ruleResolver);
          linkerArgsBuilder.addAll(linker.linkWhole(staticLibrary));
        } else {
          linkerArgsBuilder.add(FileListableLinkerInputArg.withSourcePathArg(staticLibrary));
        }
      }
    }
    final ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        linkerArgs,
        Preconditions.checkNotNull(frameworks),
        Preconditions.checkNotNull(libraries));
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type)
      throws NoSuchBuildTargetException {
    Pair<Flavor, Linker.LinkableDepType> key = new Pair<>(cxxPlatform.getFlavor(), type);
    NativeLinkableInput input = nativeLinkableCache.get(key);
    if (input == null) {
      input = getNativeLinkableInputUncached(cxxPlatform, type);
      nativeLinkableCache.put(key, input);
    }
    return input;
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    if (headerOnly) {
      return Linkage.ANY;
    }
    if (forceStatic) {
      return Linkage.STATIC;
    }
    if (provided || !getStaticPicLibrary(cxxPlatform).isPresent()) {
      return Linkage.SHARED;
    }
    return Linkage.ANY;
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
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }

    String resolvedSoname = getSoname(cxxPlatform);
    ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
      solibs.put(resolvedSoname, sharedLibrary);
    }
    return solibs.build();
  }

  @Override
  public Optional<NativeLinkTarget> getNativeLinkTarget(CxxPlatform cxxPlatform) {
    if (getPreferredLinkage(cxxPlatform) == Linkage.SHARED) {
      return Optional.absent();
    }
    return Optional.of(
        new NativeLinkTarget() {
          @Override
          public BuildTarget getBuildTarget() {
            return PrebuiltCxxLibrary.this.getBuildTarget();
          }
          @Override
          public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
            return NativeLinkTargetMode.library(getSoname(cxxPlatform));
          }
          @Override
          public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
              CxxPlatform cxxPlatform) {
            return Iterables.concat(
                getNativeLinkableDeps(cxxPlatform),
                getNativeLinkableExportedDeps(cxxPlatform));
          }
          @Override
          public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
              throws NoSuchBuildTargetException {
            return NativeLinkableInput.builder()
                .addAllArgs(StringArg.from(exportedLinkerFlags.apply(cxxPlatform)))
                .addAllArgs(
                    cxxPlatform.getLd().resolve(ruleResolver).linkWhole(
                        new SourcePathArg(
                            getResolver(),
                            getStaticPicLibrary(cxxPlatform).get())))
                .build();
          }
          @Override
          public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
            return Optional.absent();
          }
        });
  }

  public ImmutableList<String> getExportedLinkerFlags(CxxPlatform cxxPlatform) {
    return exportedLinkerFlags.apply(cxxPlatform);
  }
}
