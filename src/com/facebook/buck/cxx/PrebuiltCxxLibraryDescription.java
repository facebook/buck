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
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
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
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class PrebuiltCxxLibraryDescription
    implements DescriptionWithTargetGraph<PrebuiltCxxLibraryDescriptionArg>,
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
      BuildTarget target, AbstractPrebuiltCxxLibraryDescriptionArg args) {
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

  public static String getSoname(
      BuildTarget target, CxxPlatform cxxPlatform, Optional<String> soname) {
    return soname.orElse(
        String.format("lib%s.%s", target.getShortName(), cxxPlatform.getSharedLibraryExtension()));
  }

  /**
   * @return a {@link HeaderSymlinkTree} for the exported headers of this prebuilt C/C++ library.
   */
  private static HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        graphBuilder,
        cxxPlatform,
        parseExportedHeaders(buildTarget, graphBuilder, cxxPlatform, args),
        HeaderVisibility.PUBLIC,
        true);
  }

  private static ImmutableMap<Path, SourcePath> parseExportedHeaders(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      PrebuiltCxxLibraryDescriptionArg args) {
    ImmutableMap.Builder<String, SourcePath> headers = ImmutableMap.builder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyHeaders(
            buildTarget, ruleFinder, pathResolver, "exported_headers", args.getExportedHeaders()));
    headers.putAll(
        CxxDescriptionEnhancer.parseOnlyPlatformHeaders(
            buildTarget,
            graphBuilder,
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
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      PrebuiltCxxLibraryDescriptionArg args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    PrebuiltCxxLibraryPaths paths = getPaths(buildTarget, args);

    String soname = getSoname(buildTarget, cxxPlatform, args.getSoname());

    // Use the static PIC variant, if available.
    Optional<SourcePath> staticPicLibraryPath =
        paths.getStaticPicLibrary(
            projectFilesystem, graphBuilder, cellRoots, cxxPlatform, selectedVersions);
    Optional<SourcePath> staticLibraryPath =
        paths.getStaticLibrary(
            projectFilesystem, graphBuilder, cellRoots, cxxPlatform, selectedVersions);
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
        BuildTargetPaths.getGenPath(projectFilesystem, sharedTarget, "%s").resolve(soname);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
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
                cxxPlatform.getLd().resolve(graphBuilder).linkWhole(SourcePathArg.of(library)))
            .addAllArgs(
                StringArg.from(
                    CxxFlags.getFlagsWithPlatformMacroExpansion(
                        args.getExportedPostLinkerFlags(),
                        args.getExportedPostPlatformLinkerFlags(),
                        cxxPlatform)))
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
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      PrebuiltCxxLibraryDescriptionArg args) {

    // If the shared library is prebuilt, just return a reference to it.
    PrebuiltCxxLibraryPaths paths = getPaths(target, args);
    Optional<SourcePath> sharedLibraryPath =
        paths.getSharedLibrary(filesystem, graphBuilder, cellRoots, cxxPlatform, selectedVersions);
    if (sharedLibraryPath.isPresent()) {
      return sharedLibraryPath.get();
    }

    // Otherwise, generate it's build rule.
    CxxLink sharedLibrary =
        (CxxLink)
            graphBuilder.requireRule(
                target.withAppendedFlavors(cxxPlatform.getFlavor(), Type.SHARED.getFlavor()));

    return sharedLibrary.getSourcePathToOutput();
  }

  private BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
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

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SourcePath sharedLibrary =
        requireSharedLibrary(
            baseTarget,
            graphBuilder,
            cellRoots,
            projectFilesystem,
            cxxPlatform,
            selectedVersions,
            args);

    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params.get())
        .createSharedInterfaceLibraryFromLibrary(
            baseTarget.withAppendedFlavors(
                Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            sharedLibrary);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
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

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
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
            new SourcePathRuleFinder(graphBuilder),
            graphBuilder,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue() == Type.SHARED) {
        return createSharedLibraryBuildRule(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cellRoots,
            platform.get().getValue(),
            selectedVersions,
            args);
      } else if (type.get().getValue() == Type.SHARED_INTERFACE) {
        return createSharedLibraryInterface(
            baseTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            platform.get().getValue(),
            selectedVersions,
            args);
      }
    }

    // Build up complete list of exported preprocessor flags (including versioned flags).
    ImmutableList.Builder<StringWithMacros> exportedPreprocessorFlagsBuilder =
        ImmutableList.builder();
    exportedPreprocessorFlagsBuilder.addAll(args.getExportedPreprocessorFlags());
    selectedVersions.ifPresent(
        versions ->
            args.getVersionedExportedPreprocessorFlags()
                .getMatchingValues(versions)
                .forEach(exportedPreprocessorFlagsBuilder::addAll));
    ImmutableList<StringWithMacros> exportedPreprocessorFlags =
        exportedPreprocessorFlagsBuilder.build();

    // Build up complete list of exported platform preprocessor flags (including versioned flags).
    PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>
        exportedPlatformPreprocessorFlagsBuilder = PatternMatchedCollection.builder();
    args.getExportedPlatformPreprocessorFlags()
        .getPatternsAndValues()
        .forEach(
            pair ->
                exportedPlatformPreprocessorFlagsBuilder.add(pair.getFirst(), pair.getSecond()));
    selectedVersions.ifPresent(
        versions ->
            args.getVersionedExportedPlatformPreprocessorFlags()
                .getMatchingValues(versions)
                .forEach(
                    flags ->
                        flags
                            .getPatternsAndValues()
                            .forEach(
                                pair ->
                                    exportedPlatformPreprocessorFlagsBuilder.add(
                                        pair.getFirst(), pair.getSecond()))));
    PatternMatchedCollection<ImmutableList<StringWithMacros>> exportedPlatformPreprocessorFlags =
        exportedPlatformPreprocessorFlagsBuilder.build();

    // Build up complete list of exported language preprocessor flags (including versioned flags).
    ImmutableListMultimap.Builder<CxxSource.Type, StringWithMacros>
        exportedLangPreprocessorFlagsBuilder = ImmutableListMultimap.builder();
    args.getExportedLangPreprocessorFlags().forEach(exportedLangPreprocessorFlagsBuilder::putAll);
    selectedVersions.ifPresent(
        versions ->
            args.getVersionedExportedLangPreprocessorFlags()
                .getMatchingValues(versions)
                .forEach(value -> value.forEach(exportedLangPreprocessorFlagsBuilder::putAll)));
    ImmutableMap<CxxSource.Type, Collection<StringWithMacros>> exportedLangPreprocessorFlags =
        exportedLangPreprocessorFlagsBuilder.build().asMap();

    // Build up complete list of exported language-platform preprocessor flags (including versioned
    // flags).
    ListMultimap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        exportedLangPlatformPreprocessorFlagsBuilder = ArrayListMultimap.create();
    args.getExportedLangPlatformPreprocessorFlags()
        .forEach(exportedLangPlatformPreprocessorFlagsBuilder::put);
    selectedVersions.ifPresent(
        versions ->
            args.getVersionedExportedLangPlatformPreprocessorFlags()
                .getMatchingValues(versions)
                .forEach(
                    value -> value.forEach(exportedLangPlatformPreprocessorFlagsBuilder::put)));
    ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        exportedLangPlatformPreprocessorFlags =
            ImmutableMap.copyOf(
                Maps.transformValues(
                    exportedLangPlatformPreprocessorFlagsBuilder.asMap(),
                    PatternMatchedCollection::concat));

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    PrebuiltCxxLibraryPaths paths = getPaths(buildTarget, args);
    return new PrebuiltCxxLibrary(buildTarget, projectFilesystem, params) {

      private final Cache<NativeLinkableCacheKey, NativeLinkableInput> nativeLinkableCache =
          CacheBuilder.newBuilder().build();

      private final TransitiveCxxPreprocessorInputCache transitiveCxxPreprocessorInputCache =
          new TransitiveCxxPreprocessorInputCache(this);

      private boolean hasHeaders(CxxPlatform cxxPlatform) {
        if (!args.getExportedHeaders().isEmpty()) {
          return true;
        }
        for (SourceSortedSet sourceList :
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
                CxxFlags.getLanguageFlagsWithMacros(
                    exportedPreprocessorFlags,
                    exportedPlatformPreprocessorFlags,
                    exportedLangPreprocessorFlags,
                    exportedLangPlatformPreprocessorFlags,
                    cxxPlatform),
                flag ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        getBuildTarget(), cellRoots, graphBuilder, cxxPlatform, flag)));
      }

      @Override
      public ImmutableList<String> getExportedLinkerFlags(CxxPlatform cxxPlatform) {
        return CxxFlags.getFlagsWithPlatformMacroExpansion(
            args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), cxxPlatform);
      }

      @Override
      public ImmutableList<String> getExportedPostLinkerFlags(CxxPlatform cxxPlatform) {
        return CxxFlags.getFlagsWithPlatformMacroExpansion(
            args.getExportedPostLinkerFlags(),
            args.getExportedPostPlatformLinkerFlags(),
            cxxPlatform);
      }

      private String getSoname(CxxPlatform cxxPlatform) {
        return PrebuiltCxxLibraryDescription.getSoname(
            getBuildTarget(), cxxPlatform, args.getSoname());
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
          CxxPlatform cxxPlatform, boolean link, ActionGraphBuilder graphBuilder) {
        if (link
            && args.isSupportsSharedLibraryInterface()
            && cxxPlatform.getSharedLibraryInterfaceParams().isPresent()) {
          BuildTarget target =
              buildTarget.withAppendedFlavors(
                  cxxPlatform.getFlavor(), Type.SHARED_INTERFACE.getFlavor());
          BuildRule rule = graphBuilder.requireRule(target);
          return Preconditions.checkNotNull(rule.getSourcePathToOutput());
        }
        return PrebuiltCxxLibraryDescription.this.requireSharedLibrary(
            buildTarget,
            graphBuilder,
            cellRoots,
            projectFilesystem,
            cxxPlatform,
            selectedVersions,
            args);
      }

      /**
       * @return the {@link Optional} containing a {@link SourcePath} representing the actual static
       *     library.
       */
      @Override
      Optional<SourcePath> getStaticLibrary(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return paths.getStaticLibrary(
            getProjectFilesystem(), graphBuilder, cellRoots, cxxPlatform, selectedVersions);
      }

      /**
       * @return the {@link Optional} containing a {@link SourcePath} representing the actual static
       *     PIC library.
       */
      @Override
      Optional<SourcePath> getStaticPicLibrary(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        Optional<SourcePath> staticPicLibraryPath =
            paths.getStaticPicLibrary(
                getProjectFilesystem(), graphBuilder, cellRoots, cxxPlatform, selectedVersions);
        // If a specific static-pic variant isn't available, then just use the static variant.
        return staticPicLibraryPath.isPresent()
            ? staticPicLibraryPath
            : getStaticLibrary(cxxPlatform, graphBuilder);
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
        CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

        if (hasHeaders(cxxPlatform)) {
          CxxPreprocessables.addHeaderSymlinkTree(
              builder,
              getBuildTarget(),
              graphBuilder,
              cxxPlatform,
              HeaderVisibility.PUBLIC,
              CxxPreprocessables.IncludeType.SYSTEM);
        }
        builder.putAllPreprocessorFlags(getExportedPreprocessorFlags(cxxPlatform));
        builder.addAllFrameworks(args.getFrameworks());
        for (SourcePath includePath :
            paths.getIncludeDirs(
                projectFilesystem, graphBuilder, cellRoots, cxxPlatform, selectedVersions)) {
          builder.addIncludes(CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, includePath));
        }
        return builder.build();
      }

      @Override
      public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform, graphBuilder);
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
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableList.of();
        }
        return getNativeLinkableExportedDeps(graphBuilder);
      }

      private NativeLinkableInput computeNativeLinkableInputUncached(
          NativeLinkableCacheKey key, ActionGraphBuilder graphBuilder) {
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
                getPreferredLinkage(cxxPlatform, graphBuilder) != Linkage.STATIC);
            SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, true, graphBuilder);
            if (args.getLinkWithoutSoname()) {
              if (!(sharedLibrary instanceof PathSourcePath)) {
                throw new HumanReadableException(
                    "%s: can only link prebuilt DSOs without sonames", getBuildTarget());
              }
              linkerArgsBuilder.add(new RelativeLinkArg((PathSourcePath) sharedLibrary));
            } else {
              linkerArgsBuilder.add(
                  SourcePathArg.of(requireSharedLibrary(cxxPlatform, true, graphBuilder)));
            }
          } else {
            Preconditions.checkState(
                getPreferredLinkage(cxxPlatform, graphBuilder) != Linkage.SHARED);
            Optional<SourcePath> staticLibraryPath =
                type == Linker.LinkableDepType.STATIC_PIC
                    ? getStaticPicLibrary(cxxPlatform, graphBuilder)
                    : getStaticLibrary(cxxPlatform, graphBuilder);
            SourcePathArg staticLibrary =
                SourcePathArg.of(
                    staticLibraryPath.orElseThrow(
                        () ->
                            new HumanReadableException(
                                "Could not find static library for %s.", getBuildTarget())));
            if (args.isLinkWhole() || forceLinkWhole) {
              Linker linker = cxxPlatform.getLd().resolve(graphBuilder);
              linkerArgsBuilder.addAll(linker.linkWhole(staticLibrary));
            } else {
              linkerArgsBuilder.add(FileListableLinkerInputArg.withSourcePathArg(staticLibrary));
            }
          }
        }

        // Add any post exported flags, if any.
        linkerArgsBuilder.addAll(StringArg.from(getExportedPostLinkerFlags(cxxPlatform)));

        ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

        return NativeLinkableInput.of(linkerArgs, args.getFrameworks(), args.getLibraries());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type,
          boolean forceLinkWhole,
          ImmutableSet<LanguageExtensions> languageExtensions,
          ActionGraphBuilder graphBuilder) {
        NativeLinkableCacheKey key =
            NativeLinkableCacheKey.of(cxxPlatform.getFlavor(), type, forceLinkWhole, cxxPlatform);
        try {
          return nativeLinkableCache.get(
              key, () -> computeNativeLinkableInputUncached(key, graphBuilder));
        } catch (ExecutionException e) {
          throw new UncheckedExecutionException(e.getCause());
        }
      }

      @Override
      public NativeLinkable.Linkage getPreferredLinkage(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
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
            paths.getLinkage(projectFilesystem, graphBuilder, cellRoots, cxxPlatform);
        if (inferredLinkage.isPresent()) {
          return inferredLinkage.get();
        }
        return Linkage.ANY;
      }

      @Override
      public boolean supportsOmnibusLinking(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return args.getSupportsMergedLinking()
            .orElse(getPreferredLinkage(cxxPlatform, graphBuilder) != Linkage.SHARED);
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
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        if (!isPlatformSupported(cxxPlatform)) {
          return ImmutableMap.of();
        }

        String resolvedSoname = getSoname(cxxPlatform);
        ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
        if (!args.isHeaderOnly() && !args.isProvided()) {
          SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform, false, graphBuilder);
          solibs.put(resolvedSoname, sharedLibrary);
        }
        return solibs.build();
      }

      @Override
      public Optional<NativeLinkTarget> getNativeLinkTarget(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        if (getPreferredLinkage(cxxPlatform, graphBuilder) == Linkage.SHARED) {
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
                  CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
                return Iterables.concat(
                    getNativeLinkableDepsForPlatform(cxxPlatform, graphBuilder),
                    getNativeLinkableExportedDepsForPlatform(cxxPlatform, graphBuilder));
              }

              @Override
              public NativeLinkableInput getNativeLinkTargetInput(
                  CxxPlatform cxxPlatform,
                  ActionGraphBuilder graphBuilder,
                  SourcePathResolver pathResolver,
                  SourcePathRuleFinder ruleFinder) {
                return NativeLinkableInput.builder()
                    .addAllArgs(StringArg.from(getExportedLinkerFlags(cxxPlatform)))
                    .addAllArgs(
                        cxxPlatform
                            .getLd()
                            .resolve(graphBuilder)
                            .linkWhole(
                                SourcePathArg.of(
                                    getStaticPicLibrary(cxxPlatform, graphBuilder).get())))
                    .addAllArgs(StringArg.from(getExportedPostLinkerFlags(cxxPlatform)))
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
    getPaths(buildTarget, constructorArg)
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

    @Value.Default
    default boolean isHeaderOnly() {
      return false;
    }

    @Value.Default
    default SourceSortedSet getExportedHeaders() {
      return SourceSortedSet.EMPTY;
    }

    @Value.Default
    default PatternMatchedCollection<SourceSortedSet> getExportedPlatformHeaders() {
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

    ImmutableList<StringWithMacros> getExportedPreprocessorFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>>
        getExportedPlatformPreprocessorFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>>
        getExportedLangPreprocessorFlags();

    ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        getExportedLangPlatformPreprocessorFlags();

    @Value.Default
    default VersionMatchedCollection<ImmutableList<StringWithMacros>>
        getVersionedExportedPreprocessorFlags() {
      return VersionMatchedCollection.of();
    }

    @Value.Default
    default VersionMatchedCollection<PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        getVersionedExportedPlatformPreprocessorFlags() {
      return VersionMatchedCollection.of();
    }

    @Value.Default
    default VersionMatchedCollection<ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>>>
        getVersionedExportedLangPreprocessorFlags() {
      return VersionMatchedCollection.of();
    }

    @Value.Default
    default VersionMatchedCollection<
            ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>>
        getVersionedExportedLangPlatformPreprocessorFlags() {
      return VersionMatchedCollection.of();
    }

    ImmutableList<String> getExportedLinkerFlags();

    ImmutableList<String> getExportedPostLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPostPlatformLinkerFlags() {
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
  }
}
