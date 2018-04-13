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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableCacheKey;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroFinder;
import com.facebook.buck.model.macros.StringMacroCombiner;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.macros.FunctionMacroReplacer;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class PrebuiltCxxLibraryDescription
    implements Description<PrebuiltCxxLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<
            PrebuiltCxxLibraryDescription.AbstractPrebuiltCxxLibraryDescriptionArg>,
        VersionPropagator<PrebuiltCxxLibraryDescriptionArg> {

  enum Type implements FlavorConvertible {
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    SHARED_INTERFACE(InternalFlavor.of("shared-interface"));

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public PrebuiltCxxLibraryDescription(
      ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<PrebuiltCxxLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltCxxLibraryDescriptionArg.class;
  }

  private PrebuiltCxxLibraryPaths getPaths(
      BuildTarget target,
      AbstractPrebuiltCxxLibraryDescriptionArg args,
      Optional<String> versionSubDir) {
    if (args.isNewApiUsed() && args.isOldApiUsed()) {
      throw new HumanReadableException("%s: cannot use both old and new APIs", target);
    }
    if (args.isOldApiUsed()) {
      if (!cxxBuckConfig.isDeprecatedPrebuiltCxxLibraryApiEnabled()) {
        throw new HumanReadableException(
            "%s(%s) uses the deprecated API, but `cxx.enable_deprecated_prebuilt_cxx_library_api` "
                + "isn't set.  Please see the `prebuilt_cxx_library` documentation for details and "
                + "examples on how to port to the new API.",
            Description.getBuildRuleType(this).toString(), target);
      }
      return DeprecatedPrebuiltCxxLibraryPaths.builder()
          .setTarget(target)
          .setVersionSubdir(versionSubDir)
          .setIncludeDirs(args.getIncludeDirs().orElse(ImmutableList.of()))
          .setLibDir(args.getLibDir())
          .setLibName(args.getLibName())
          .build();
    }
    return NewPrebuiltCxxLibraryPaths.builder()
        .setTarget(target)
        .setHeaderDirs(args.getHeaderDirs())
        .setPlatformHeaderDirs(args.getPlatformHeaderDirs())
        .setVersionedHeaderDirs(args.getVersionedHeaderDirs())
        .setSharedLib(args.getSharedLib())
        .setPlatformSharedLib(args.getPlatformSharedLib())
        .setVersionedSharedLib(args.getVersionedSharedLib())
        .setStaticLib(args.getStaticLib())
        .setPlatformStaticLib(args.getPlatformStaticLib())
        .setVersionedStaticLib(args.getVersionedStaticLib())
        .setStaticPicLib(args.getStaticPicLib())
        .setPlatformStaticPicLib(args.getPlatformStaticPicLib())
        .setVersionedStaticPicLib(args.getVersionedStaticPicLib())
        .build();
  }

  // Platform unlike most macro expanders needs access to the cxx build flavor.
  // Because of that it can't be like normal expanders. So just create a handler here.
  private static Function<String, String> getMacroExpander(
      BuildTarget target, CxxPlatform cxxPlatform) {
    return str -> {
      try {
        return MacroFinder.replace(
            ImmutableMap.of(
                "platform", new FunctionMacroReplacer<>(f -> cxxPlatform.getFlavor().toString())),
            str,
            true,
            new StringMacroCombiner());
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s in \"%s\"", target, e.getMessage(), str);
      }
    };
  }

  public static String getSoname(
      BuildTarget target,
      CxxPlatform cxxPlatform,
      Optional<String> soname,
      Optional<String> libName) {
    return soname.orElse(
        String.format(
            "lib%s.%s",
            libName.map(getMacroExpander(target, cxxPlatform)).orElse(target.getShortName()),
            cxxPlatform.getSharedLibraryExtension()));
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this prebuilt C/C++ library.
   */
  private static HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        resolver,
        cxxPlatform,
        parseExportedHeaders(buildTarget, resolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC,
        true);
  }

  private static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyHeaders(
            buildTarget, ruleFinder, pathResolver, "exported_headers", args.getExportedHeaders()));
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyPlatformHeaders(
            buildTarget,
            resolver,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            "exported_headers",
            args.getExportedHeaders(),
            "exported_platform, headers",
            args.getExportedPlatformHeaders()));
    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        headers.build());
  }

  /** @return a {@link CxxLink} rule for a shared library version of this prebuilt C/C++ library. */
  private BuildRule createSharedLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Optional<String> versionSubDir,
      PrebuiltCxxLibraryDescriptionArg args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    PrebuiltCxxLibraryPaths paths = getPaths(buildTarget, args, versionSubDir);

    String soname = getSoname(buildTarget, cxxPlatform, args.getSoname(), args.getLibName());

    // Use the static PIC variant, if available.
    Optional<SourcePath> staticPicLibraryPath =
        paths.getStaticPicLibrary(
            projectFilesystem, ruleResolver, cellRoots, cxxPlatform, selectedVersions);
    Optional<SourcePath> staticLibraryPath =
        paths.getStaticLibrary(
            projectFilesystem, ruleResolver, cellRoots, cxxPlatform, selectedVersions);
    SourcePath library =
        staticPicLibraryPath.orElseGet(
            () ->
                staticLibraryPath.orElseThrow(
                    () ->
                        new HumanReadableException(
                            "Could not find static library for %s.", buildTarget)));

    // Otherwise, we need to build it from the static lib.
    BuildTarget sharedTarget =
        buildTarget.withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);

    // If not, setup a single link rule to link it from the static lib.
    Path builtSharedLibraryPath =
        BuildTargets.getGenPath(projectFilesystem, sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        ruleResolver,
        pathResolver,
        ruleFinder,
        sharedTarget,
        Linker.LinkType.SHARED,
        Optional.of(soname),
        builtSharedLibraryPath,
        ImmutableList.of(),
        Linker.LinkableDepType.SHARED,
        CxxLinkOptions.of(),
        FluentIterable.from(params.getBuildDeps()).filter(NativeLinkable.class),
        Optional.empty(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                StringArg.from(
                    CxxFlags.getFlagsWithPlatformMacroExpansion(
                        args.getExportedLinkerFlags(),
                        args.getExportedPlatformLinkerFlags(),
                        cxxPlatform)))
            .addAllArgs(
                cxxPlatform.getLd().resolve(ruleResolver).linkWhole(SourcePathArg.of(library)))
            .build(),
        Optional.empty(),
        cellRoots);
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(
      BuildTarget target,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Optional<String> versionSubdir,
      PrebuiltCxxLibraryDescriptionArg args) {

    // If the shared library is prebuilt, just return a reference to it.
    PrebuiltCxxLibraryPaths paths = getPaths(target, args, versionSubdir);
    Optional<SourcePath> sharedLibraryPath =
        paths.getSharedLibrary(filesystem, resolver, cellRoots, cxxPlatform, selectedVersions);
    if (sharedLibraryPath.isPresent()) {
      return sharedLibraryPath.get();
    }

    // Otherwise, generate it's build rule.
    CxxLink sharedLibrary =
        (CxxLink)
            resolver.requireRule(
                target.withAppendedFlavors(cxxPlatform.getFlavor(), Type.SHARED.getFlavor()));

    return sharedLibrary.getSourcePathToOutput();
  }

  private BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Optional<String> versionSubdir,
      PrebuiltCxxLibraryDescriptionArg args) {

    if (!args.isSupportsSharedLibraryInterface()) {
      throw new HumanReadableException(
          "%s: rule does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    Optional<SharedLibraryInterfaceParams> params = cxxPlatform.getSharedLibraryInterfaceParams();
    if (!params.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SourcePath sharedLibrary =
        requireSharedLibrary(
            baseTarget,
            resolver,
            cellRoots,
            projectFilesystem,
            cxxPlatform,
            selectedVersions,
            versionSubdir,
            args);

    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params.get())
        .createSharedInterfaceLibraryFromLibrary(
            baseTarget.withAppendedFlavors(
                Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            resolver,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            sharedLibrary);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltCxxLibraryDescriptionArg args) {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<Map.Entry<Flavor, CxxPlatform>> platform =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getCxxPlatforms()
            .getFlavorAndValue(buildTarget);

    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        context.getTargetGraph().get(buildTarget).getSelectedVersions();
    Optional<String> versionSubdir =
        selectedVersions.isPresent() && args.getVersionedSubDir().isPresent()
            ? Optional.of(
                args.getVersionedSubDir()
                    .get()
                    .getOnlyMatchingValue(
                        String.format("%s: %s", buildTarget, "versioned_sub_dir"),
                        selectedVersions.get()))
            : Optional.empty();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    BuildRuleResolver ruleResolver = context.getBuildRuleResolver();
    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent()) {
      Preconditions.checkState(platform.isPresent());
      BuildTarget baseTarget =
          buildTarget.withoutFlavors(type.get().getKey(), platform.get().getKey());
      if (type.get().getValue() == Type.EXPORTED_HEADERS) {
        return createExportedHeaderSymlinkTreeBuildRule(
            buildTarget,
            projectFilesystem,
            new SourcePathRuleFinder(ruleResolver),
            ruleResolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue() == Type.SHARED) {
        return createSharedLibraryBuildRule(
            buildTarget,
            projectFilesystem,
            params,
            ruleResolver,
            cellRoots,
            platform.get().getValue(),
            selectedVersions,
            versionSubdir,
            args);
      } else if (type.get().getValue() == Type.SHARED_INTERFACE) {
        return createSharedLibraryInterface(
            baseTarget,
            projectFilesystem,
            ruleResolver,
            cellRoots,
            platform.get().getValue(),
            selectedVersions,
            versionSubdir,
            args);
      }
    }

    if (selectedVersions.isPresent() && args.getVersionedSubDir().isPresent()) {
      ImmutableList<String> versionSubDirs =
          args.getVersionedSubDir()
              .orElse(VersionMatchedCollection.of())
              .getMatchingValues(selectedVersions.get());
      if (versionSubDirs.size() != 1) {
        throw new HumanReadableException(
            "%s: could not get a single version sub dir: %s, %s, %s",
            buildTarget, args.getVersionedSubDir(), versionSubDirs, selectedVersions);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    PrebuiltCxxLibraryPaths paths = getPaths(buildTarget, args, versionSubdir);
    return new PrebuiltCxxLibrary(buildTarget, projectFilesystem, params) {

      private final Cache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
          CacheBuilder.newBuilder().build();

      private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
          new TransitiveCxxPreprocessorInputCache(this);

      private boolean hasHeaders(CxxPlatform cxxPlatform) {
        if (!args.getExportedHeaders().isEmpty()) {
          return true;
        }
        for (SourceList sourceList :
            args.getExportedPlatformHeaders()
                .getMatchingValues(cxxPlatform.getFlavor().toString())) {
          if (!sourceList.isEmpty()) {
            return true;
          }
        }
        return false;
      }

      private ImmutableListMultimap<CxxSource.Type, Arg> getExportedPreprocessorFlags(
          CxxPlatform cxxPlatform) {
        return ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlags(
                    args.getExportedPreprocessorFlags(),
                    args.getExportedPlatformPreprocessorFlags(),
                    args.getExportedLangPreprocessorFlags(),
                    cxxPlatform),
                StringArg::of));
      }

      @Override
      public ImmutableList<String> getExportedLinkerFlags(CxxPlatform cxxPlatform) {
        return CxxFlags.getFlagsWithPlatformMacroExpansion(
            args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), cxxPlatform);
      }

      private String getSoname(CxxPlatform cxxPlatform) {
        return PrebuiltCxxLibraryDescription.getSoname(
            getBuildTarget(), cxxPlatform, args.getSoname(), args.getLibName());
      }

      private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
        return !args.getSupportedPlatformsRegex().isPresent()
            || args.getSupportedPlatformsRegex()
                .get()
                .matcher(cxxPlatform.getFlavor().toString())
                .find();
      }

      /**
       * Makes sure all build rules needed to produce the shared library are added to the action
       * graph.
       *
       * @return the {@link SourcePath} representing the actual shared library.
       */
      private SourcePath requireSharedLibrary(
          CxxPlatform cxxPlatform, boolean link, BuildRuleResolver ruleResolver) {
        if (link
            && args.isSupportsSharedLibraryInterface()
            && cxxPlatform.getSharedLibraryInterfaceParams().isPresent()) {
          BuildTarget target =
              buildTarget.withAppendedFlavors(
                  cxxPlatform.getFlavor(), Type.SHARED_INTERFACE.getFlavor());
          BuildRule rule = ruleResolver.requireRule(target);
          return Preconditions.checkNotNull(rule.getSourcePathToOutput());
        }
        return PrebuiltCxxLibraryDescription.this.requireSharedLibrary(
            buildTarget,
            ruleResolver,
            cellRoots,
            projectFilesystem,
            cxxPlatform,
            selectedVersions,
            versionSubdir,
            args);
      }

      /**
       * @return the {@link Optional} containing a {@link SourcePath} representing the actual static
       *     library.
       */
      @Override
      Optional<SourcePath> getStaticLibrary(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return paths.getStaticLibrary(
            getProjectFilesystem(), ruleResolver, cellRoots, cxxPlatform, selectedVersions);
      }

      /**
       * @return the {@link Optional} containing a {@link SourcePath} representing the actual static
       *     PIC library.
       */
      @Override
      Optional<SourcePath> getStaticPicLibrary(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        Optional<SourcePath> staticPicLibraryPath =
            paths.getStaticPicLibrary(
                getProjectFilesystem(), ruleResolver, cellRoots, cxxPlatform, selectedVersions);
        // If a specific static-pic variant isn't available, then just use the static variant.
        return staticPicLibraryPath.isPresent()
            ? staticPicLibraryPath
            : getStaticLibrary(cxxPlatform, ruleResolver);
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
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

        if (hasHeaders(cxxPlatform)) {
          CxxPreprocessables.addHeaderSymlinkTree(
              builder,
              getBuildTarget(),
              ruleResolver,
              cxxPlatform,
              HeaderVisibility.PUBLIC,
              CxxPreprocessables.IncludeType.SYSTEM);
        }
        builder.putAllPreprocessorFlags(getExportedPreprocessorFlags(cxxPlatform));
        builder.addAllFrameworks(args.getFrameworks());
        for (SourcePath includePath :
            paths.getIncludeDirs(
                projectFilesystem, ruleResolver, cellRoots, cxxPlatform, selectedVersions)) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includePath));
        }
        return builder.build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, ruleResolver);
      }

      @Override
      public Iterable<NativeLinkable> getNativeLinkableDeps(BuildRuleResolver ruleResolver) {
        return getDeclaredDeps()
            .stream()
            .filter(r -> r instanceof NativeLinkable)
            .map(r -> (NativeLinkable) r)
            .collect(ImmutableList.toImmutableList());
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
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
          BuildRuleResolver ruleResolver) {
        return args.getExportedDeps()
            .stream()
            .map(ruleResolver::getRule)
            .filter(r -> r instanceof NativeLinkable)
            .map(r -> (NativeLinkable) r)
            .collect(ImmutableList.toImmutableList());
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableExportedDeps(ruleResolver);
      }

      private NativeLinkableInput computeNativeLinkableInputUncached(
          NativeLinkableCacheKey key, BuildRuleResolver ruleResolver) {
        CxxPlatform cxxPlatform = key.getCxxPlatform();

        if (!isPlatformSupported(cxxPlatform)) {
          return NativeLinkableInput.of();
        }

        Linker.LinkableDepType type = key.getType();
        boolean forceLinkWhole = key.getForceLinkWhole();

        // Build the library path and linker arguments that we pass through the
        // {@link NativeLinkable} interface for linking.
        ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
        linkerArgsBuilder.addAll(
            StringArg.from(Preconditions.checkNotNull(getExportedLinkerFlags(cxxPlatform))));

        if (!args.isHeaderOnly()) {
          if (type == Linker.LinkableDepType.SHARED) {
            Preconditions.checkState(
                getPreferredLinkage(cxxPlatform, ruleResolver) != Linkage.STATIC);
            SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, true, ruleResolver);
            if (args.getLinkWithoutSoname()) {
              if (!(sharedLibrary instanceof PathSourcePath)) {
                throw new HumanReadableException(
                    "%s: can only link prebuilt DSOs without sonames", getBuildTarget());
              }
              linkerArgsBuilder.add(new RelativeLinkArg((PathSourcePath) sharedLibrary));
            } else {
              linkerArgsBuilder.add(
                  SourcePathArg.of(requireSharedLibrary(cxxPlatform, true, ruleResolver)));
            }
          } else {
            Preconditions.checkState(
                getPreferredLinkage(cxxPlatform, ruleResolver) != Linkage.SHARED);
            Optional<SourcePath> staticLibraryPath =
                type == Linker.LinkableDepType.STATIC_PIC
                    ? getStaticPicLibrary(cxxPlatform, ruleResolver)
                    : getStaticLibrary(cxxPlatform, ruleResolver);
            SourcePathArg staticLibrary =
                SourcePathArg.of(
                    staticLibraryPath.orElseThrow(
                        () ->
                            new HumanReadableException(
                                "Could not find static library for %s.", getBuildTarget())));
            if (args.isLinkWhole() || forceLinkWhole) {
              Linker linker = cxxPlatform.getLd().resolve(ruleResolver);
              linkerArgsBuilder.addAll(linker.linkWhole(staticLibrary));
            } else {
              linkerArgsBuilder.add(FileListableLinkerInputArg.withSourcePathArg(staticLibrary));
            }
          }
        }
        ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

        return NativeLinkableInput.of(linkerArgs, args.getFrameworks(), args.getLibraries());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          ImmutableSet<LanguageExtensions> languageExtensions,
          BuildRuleResolver ruleResolver) {
        NativeLinkableCacheKey key =
            NativeLinkableCacheKey.of(cxxPlatform.getFlavor(), type, forceLinkWhole, cxxPlatform);
        try {
          return nativeLinkableCache.get(
              key, () -> computeNativeLinkableInputUncached(key, ruleResolver));
        } catch (ExecutionException e) {
          throw new UncheckedExecutionException(e.getCause());
        }
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        if (args.isHeaderOnly()) {
          return Linkage.ANY;
        }
        if (args.isForceStatic()) {
          return Linkage.STATIC;
        }
        if (args.getPreferredLinkage().orElse(Linkage.ANY) != Linkage.ANY) {
          return args.getPreferredLinkage().get();
        }
        if (args.isProvided()) {
          return Linkage.SHARED;
        }
        Optional<Linkage> inferredLinkage =
            paths.getLinkage(projectFilesystem, ruleResolver, cellRoots, cxxPlatform);
        if (inferredLinkage.isPresent()) {
          return inferredLinkage.get();
        }
        return Linkage.ANY;
      }

      @Override
      public boolean supportsOmnibusLinking(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        return args.getSupportsMergedLinking()
            .orElse(getPreferredLinkage(cxxPlatform, ruleResolver) != Linkage.SHARED);
      }

      @Override
      public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
        return AndroidPackageableCollector.getPackageableRules(params.getBuildDeps());
      }

      @Override
      public void addToCollector(AndroidPackageableCollector collector) {
        if (args.getCanBeAsset()) {
          collector.addNativeLinkableAsset(this);
        } else {
          collector.addNativeLinkable(this);
        }
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableMap.of();
        }

        String resolvedSoname = getSoname(cxxPlatform);
        ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
        if (!args.isHeaderOnly() && !args.isProvided()) {
          SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, false, ruleResolver);
          solibs.put(resolvedSoname, sharedLibrary);
        }
        return solibs.build();
      }

      @Override
      public Optional<NativeLinkTarget> getNativeLinkTarget(
          CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
        if (getPreferredLinkage(cxxPlatform, ruleResolver) == Linkage.SHARED) {
          return Optional.empty();
        }
        return Optional.of(
            new NativeLinkTarget() {
              @Override
              public BuildTarget getBuildTarget() {
                return buildTarget;
              }

              @Override
              public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
                return NativeLinkTargetMode.library(getSoname(cxxPlatform));
              }

              @Override
              public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
                  CxxPlatform cxxPlatform, BuildRuleResolver ruleResolver) {
                return Iterables.concat(
                    getNativeLinkableDepsForPlatform(cxxPlatform, ruleResolver),
                    getNativeLinkableExportedDepsForPlatform(cxxPlatform, ruleResolver));
              }

              @Override
              public NativeLinkableInput getNativeLinkTargetInput(
                  CxxPlatform cxxPlatform,
                  BuildRuleResolver ruleResolver,
                  SourcePathResolver pathResolver,
                  SourcePathRuleFinder ruleFinder) {
                return NativeLinkableInput.builder()
                    .addAllArgs(StringArg.from(getExportedLinkerFlags(cxxPlatform)))
                    .addAllArgs(
                        cxxPlatform
                            .getLd()
                            .resolve(ruleResolver)
                            .linkWhole(
                                SourcePathArg.of(
                                    getStaticPicLibrary(cxxPlatform, ruleResolver).get())))
                    .build();
              }

              @Override
              public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
                return Optional.empty();
              }
            });
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractPrebuiltCxxLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    getPaths(buildTarget, constructorArg, Optional.empty())
        .findParseTimeDeps(cellRoots, extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltCxxLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {

    // New API.
    Optional<ImmutableList<SourcePath>> getHeaderDirs();

    Optional<PatternMatchedCollection<ImmutableList<SourcePath>>> getPlatformHeaderDirs();

    Optional<VersionMatchedCollection<ImmutableList<SourcePath>>> getVersionedHeaderDirs();

    Optional<SourcePath> getSharedLib();

    @Hint(isTargetGraphOnlyDep = true)
    Optional<PatternMatchedCollection<SourcePath>> getPlatformSharedLib();

    Optional<VersionMatchedCollection<SourcePath>> getVersionedSharedLib();

    Optional<SourcePath> getStaticLib();

    @Hint(isTargetGraphOnlyDep = true)
    Optional<PatternMatchedCollection<SourcePath>> getPlatformStaticLib();

    Optional<VersionMatchedCollection<SourcePath>> getVersionedStaticLib();

    Optional<SourcePath> getStaticPicLib();

    @Hint(isTargetGraphOnlyDep = true)
    Optional<PatternMatchedCollection<SourcePath>> getPlatformStaticPicLib();

    Optional<VersionMatchedCollection<SourcePath>> getVersionedStaticPicLib();

    // Deprecated API.
    Optional<ImmutableList<String>> getIncludeDirs();

    Optional<String> getLibName();

    Optional<String> getLibDir();

    @Value.Default
    default boolean isHeaderOnly() {
      return false;
    }

    @Value.Default
    default SourceList getExportedHeaders() {
      return SourceList.EMPTY;
    }

    @Value.Default
    default PatternMatchedCollection<SourceList> getExportedPlatformHeaders() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getHeaderNamespace();

    @Value.Default
    default boolean isProvided() {
      return false;
    }

    @Value.Default
    default boolean isLinkWhole() {
      return false;
    }

    @Value.Default
    default boolean isForceStatic() {
      return false;
    }

    Optional<NativeLinkable.Linkage> getPreferredLinkage();

    ImmutableList<String> getExportedPreprocessorFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformPreprocessorFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableMap<CxxSource.Type, ImmutableList<String>> getExportedLangPreprocessorFlags();

    ImmutableList<String> getExportedLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getSoname();

    @Value.Default
    default boolean getLinkWithoutSoname() {
      return false;
    }

    @Value.Default
    default boolean getCanBeAsset() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getFrameworks();

    @Value.NaturalOrder
    ImmutableSortedSet<FrameworkPath> getLibraries();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<VersionMatchedCollection<String>> getVersionedSubDir();

    @Value.Default
    default boolean isSupportsSharedLibraryInterface() {
      return false;
    }

    Optional<Boolean> getSupportsMergedLinking();

    default boolean isNewApiUsed() {
      return getHeaderDirs().isPresent()
          || getPlatformHeaderDirs().isPresent()
          || getVersionedHeaderDirs().isPresent()
          || getSharedLib().isPresent()
          || getPlatformSharedLib().isPresent()
          || getVersionedSharedLib().isPresent()
          || getStaticLib().isPresent()
          || getPlatformStaticLib().isPresent()
          || getVersionedStaticLib().isPresent()
          || getStaticPicLib().isPresent()
          || getPlatformStaticPicLib().isPresent()
          || getVersionedStaticPicLib().isPresent();
    }

    default boolean isOldApiUsed() {
      return getVersionedSubDir().isPresent()
          || getIncludeDirs().isPresent()
          || getLibDir().isPresent()
          || getLibName().isPresent();
    }
  }
}
