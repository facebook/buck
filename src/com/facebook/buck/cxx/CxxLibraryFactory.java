/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Predicate;

public class CxxLibraryFactory {

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;

  public CxxLibraryFactory(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
  }

  public BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams metadataRuleParams,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final CxxLibraryDescriptionArg args,
      Optional<Linker.LinkableDepType> linkableDepType,
      final Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      ImmutableSortedSet<BuildTarget> extraDeps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, CxxLibraryDescription.Type>> type =
        CxxLibraryDescription.getLibType(buildTarget);
    Optional<CxxPlatform> platform = cxxPlatforms.getValue(buildTarget);
    CxxDeps cxxDeps = CxxDeps.builder().addDeps(args.getCxxDeps()).addDeps(extraDeps).build();

    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      // XXX: This needs bundleLoader for tests..
      CxxPlatform cxxPlatform =
          platform.orElse(
              cxxPlatforms.getValue(args.getDefaultPlatform().orElse(defaultCxxFlavor)));
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
      // TODO(T21900763): We should be using `requireObjects` instead but those would not
      // necessarily be `CxxPreprocessAndCompile` rules (e.g., Swift in `apple_library`).
      ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
          requireCxxObjects(
              buildTarget.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              resolver,
              sourcePathResolver,
              ruleFinder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              PicType.PIC,
              args,
              cxxDeps.get(resolver, cxxPlatform),
              transitiveCxxPreprocessorInputFunction,
              delegate);
      return CxxCompilationDatabase.createCompilationDatabase(
          buildTarget, projectFilesystem, objects.keySet());
    } else if (buildTarget
        .getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          platform.isPresent()
              ? buildTarget
              : buildTarget.withAppendedFlavors(args.getDefaultPlatform().orElse(defaultCxxFlavor)),
          projectFilesystem,
          resolver);
    } else if (CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      return CxxInferEnhancer.requireInferRule(
          buildTarget,
          projectFilesystem,
          resolver,
          cellRoots,
          cxxBuckConfig,
          platform.orElse(cxxPlatforms.getValue(defaultCxxFlavor)),
          args,
          inferBuckConfig);
    } else if (type.isPresent() && !platform.isPresent()) {
      BuildTarget untypedBuildTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);
      switch (type.get().getValue()) {
        case EXPORTED_HEADERS:
          Optional<HeaderMode> mode = CxxLibraryDescription.HEADER_MODE.getValue(buildTarget);
          if (mode.isPresent()) {
            return createExportedHeaderSymlinkTreeBuildRule(
                untypedBuildTarget, projectFilesystem, resolver, mode.get(), args);
          }
          break;
          // $CASES-OMITTED$
        default:
      }

    } else if (type.isPresent() && platform.isPresent()) {
      // If we *are* building a specific type of this lib, call into the type specific
      // rule builder methods.

      BuildTarget untypedBuildTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);
      switch (type.get().getValue()) {
        case HEADERS:
          return createHeaderSymlinkTreeBuildRule(
              untypedBuildTarget, projectFilesystem, resolver, platform.get(), args);
        case EXPORTED_HEADERS:
          return createExportedPlatformHeaderSymlinkTreeBuildRule(
              untypedBuildTarget, projectFilesystem, resolver, platform.get(), args);
        case SHARED:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.SHARED,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              Optional.empty(),
              blacklist,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case SHARED_INTERFACE:
          return createSharedLibraryInterface(
              untypedBuildTarget, projectFilesystem, resolver, platform.get());
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              Linker.LinkType.MACH_O_BUNDLE,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              bundleLoader,
              blacklist,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case STATIC:
          return createStaticLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              PicType.PDC,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case STATIC_PIC:
          return createStaticLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              resolver,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(resolver, platform.get()),
              PicType.PIC,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case SANDBOX_TREE:
          return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
              resolver, args, platform.get(), untypedBuildTarget, projectFilesystem);
      }
      throw new RuntimeException("unhandled library build type");
    }

    boolean hasObjectsForAnyPlatform = !args.getSrcs().isEmpty();
    Predicate<CxxPlatform> hasObjects;
    if (hasObjectsForAnyPlatform) {
      hasObjects = x -> true;
    } else {
      hasObjects =
          input ->
              !args.getPlatformSrcs().getMatchingValues(input.getFlavor().toString()).isEmpty();
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    final SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return new CxxLibrary(
        buildTarget,
        projectFilesystem,
        metadataRuleParams,
        resolver,
        args.getPrivateCxxDeps(),
        args.getExportedCxxDeps(),
        hasObjects.negate(),
        input -> {
          ImmutableList<Arg> delegateExportedLinkerFlags =
              delegate
                  .map(d -> d.getAdditionalExportedLinkerFlags(buildTarget, resolver, input))
                  .orElse(ImmutableList.of());

          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input);
          return RichStream.from(flags)
              .map(
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, resolver, input, f))
              .concat(RichStream.from(delegateExportedLinkerFlags))
              .toImmutableList();
        },
        cxxPlatform -> {
          return getSharedLibraryNativeLinkTargetInput(
              buildTarget,
              projectFilesystem,
              resolver,
              pathResolver,
              ruleFinder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              args,
              cxxDeps.get(resolver, cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(),
                  args.getExportedPlatformLinkerFlags(),
                  cxxPlatform),
              args.getFrameworks(),
              args.getLibraries(),
              transitiveCxxPreprocessorInputFunction,
              delegate);
        },
        args.getSupportedPlatformsRegex(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getForceStatic().orElse(false)
            ? NativeLinkable.Linkage.STATIC
            : args.getPreferredLinkage().orElse(NativeLinkable.Linkage.ANY),
        args.getLinkWhole().orElse(false),
        args.getSoname(),
        args.getTests(),
        args.getCanBeAsset().orElse(false),
        !buildTarget
            .getFlavors()
            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
        args.isReexportAllHeaderDependencies(),
        delegate);
  }

  private static ImmutableList<SourcePath> requireObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      Optional<CxxLibraryDescriptionDelegate> delegate) {

    // TODO(T21900747): Fix dependence on order of object paths
    ImmutableList.Builder<SourcePath> builder = ImmutableList.builder();
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> cxxObjects =
        requireCxxObjects(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            sourcePathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            pic,
            args,
            deps,
            transitivePreprocessorInputs,
            delegate);

    builder.addAll(cxxObjects.values());

    Optional<ImmutableList<SourcePath>> pluginObjectPaths =
        delegate.flatMap(p -> p.getObjectFilePaths(buildTarget, ruleResolver, cxxPlatform));
    pluginObjectPaths.ifPresent(paths -> builder.addAll(paths));

    return builder.build();
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireCxxObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      Optional<CxxLibraryDescriptionDelegate> delegate) {

    boolean shouldCreatePrivateHeadersSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                buildTarget,
                ruleResolver,
                ruleFinder,
                sourcePathResolver,
                Optional.of(cxxPlatform),
                args),
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);

    ImmutableList.Builder<HeaderSymlinkTree> privateHeaderSymlinkTrees = ImmutableList.builder();
    privateHeaderSymlinkTrees.add(headerSymlinkTree);
    delegate.ifPresent(
        d ->
            d.getPrivateHeaderSymlinkTree(buildTarget, ruleResolver, cxxPlatform)
                .ifPresent(h -> privateHeaderSymlinkTrees.add(h)));

    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree =
          CxxDescriptionEnhancer.createSandboxTree(buildTarget, ruleResolver, cxxPlatform);
    }

    // Create rule to build the object files.
    ImmutableMultimap<CxxSource.Type, Arg> compilerFlags =
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getCompilerFlags(),
                    args.getPlatformCompilerFlags(),
                    args.getLangCompilerFlags(),
                    cxxPlatform),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        buildTarget, cellRoots, ruleResolver, cxxPlatform, f)));
    return CxxSourceRuleFactory.of(
            projectFilesystem,
            buildTarget,
            ruleResolver,
            sourcePathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            CxxLibraryDescription.getPreprocessorInputsForBuildingLibrarySources(
                ruleResolver,
                cellRoots,
                buildTarget,
                args,
                cxxPlatform,
                deps,
                transitivePreprocessorInputs,
                privateHeaderSymlinkTrees.build(),
                sandboxTree),
            compilerFlags,
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            pic,
            sandboxTree)
        .requirePreprocessAndCompileRules(
            CxxDescriptionEnhancer.parseCxxSources(
                buildTarget, ruleResolver, ruleFinder, sourcePathResolver, cxxPlatform, args));
  }

  private static NativeLinkableInput getSharedLibraryNativeLinkTargetInput(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg arg,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<StringWithMacros> exportedLinkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects =
        requireObjects(
            buildTarget,
            projectFilesystem,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            cxxPlatform.getPicTypeForSharedLinking(),
            arg,
            deps,
            transitiveCxxPreprocessorInputFunction,
            delegate);

    return NativeLinkableInput.builder()
        .addAllArgs(
            RichStream.<StringWithMacros>empty()
                .concat(linkerFlags.stream())
                .concat(exportedLinkerFlags.stream())
                .map(
                    f ->
                        CxxDescriptionEnhancer.toStringWithMacrosArgs(
                            buildTarget, cellRoots, ruleResolver, cxxPlatform, f))
                .toImmutableList())
        .addAllArgs(SourcePathArg.from(objects))
        .setFrameworks(frameworks)
        .setLibraries(libraries)
        .build();
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private static CxxLink createSharedLibrary(
      BuildTarget buildTargetMaybeWithLinkerMapMode,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<String> soname,
      Optional<Linker.CxxRuntimeType> cxxRuntimeType,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {
    BuildTarget buildTargetWithoutLinkerMapMode =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(
            buildTargetMaybeWithLinkerMapMode,
            LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTargetMaybeWithLinkerMapMode));

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects =
        requireObjects(
            buildTargetWithoutLinkerMapMode,
            projectFilesystem,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            cxxPlatform.getPicTypeForSharedLinking(),
            args,
            deps,
            transitiveCxxPreprocessorInputFunction,
            delegate);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            buildTargetMaybeWithLinkerMapMode, cxxPlatform.getFlavor(), linkType);

    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, buildTargetMaybeWithLinkerMapMode, cxxPlatform);
    Path sharedLibraryPath =
        CxxDescriptionEnhancer.getSharedLibraryPath(
            projectFilesystem, sharedTarget, sharedLibrarySoname);

    ImmutableList<NativeLinkable> delegateNativeLinkables =
        delegate
            .flatMap(d -> d.getNativeLinkableExportedDeps(sharedTarget, ruleResolver, cxxPlatform))
            .orElse(ImmutableList.of());

    ImmutableList<NativeLinkable> allNativeLinkables =
        RichStream.from(deps)
            .filter(NativeLinkable.class)
            .concat(RichStream.from(delegateNativeLinkables))
            .toImmutableList();

    CxxLinkOptions linkOptions =
        CxxLinkOptions.of(
            args.getThinLto()
            );
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        ruleResolver,
        pathResolver,
        ruleFinder,
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath,
        args.getLinkerExtraOutputs(),
        linkableDepType,
        linkOptions,
        allNativeLinkables,
        cxxRuntimeType,
        bundleLoader,
        blacklist,
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                RichStream.from(linkerFlags)
                    .map(
                        f ->
                            CxxDescriptionEnhancer.toStringWithMacrosArgs(
                                sharedTarget, cellRoots, ruleResolver, cxxPlatform, f))
                    .toImmutableList())
            .addAllArgs(SourcePathArg.from(objects))
            .setFrameworks(frameworks)
            .setLibraries(libraries)
            .build(),
        Optional.empty(),
        cellRoots);
  }

  /** @return a {@link HeaderSymlinkTree} for the headers of this C/C++ library. */
  private HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {
    boolean shouldCreatePrivateHeaderSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, Optional.of(cxxPlatform), args),
        HeaderVisibility.PRIVATE,
        shouldCreatePrivateHeaderSymlinks);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      HeaderMode mode,
      CxxLibraryDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        mode,
        CxxDescriptionEnhancer.parseExportedHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, Optional.empty(), args),
        HeaderVisibility.PUBLIC);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedPlatformHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {
    boolean shouldCreatePublicHeaderSymlinks =
        args.getXcodePublicHeadersSymlinks().orElse(cxxPlatform.getPublicHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        resolver,
        cxxPlatform,
        CxxDescriptionEnhancer.parseExportedPlatformHeaders(
            buildTarget, resolver, ruleFinder, pathResolver, cxxPlatform, args),
        HeaderVisibility.PUBLIC,
        shouldCreatePublicHeaderSymlinks);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return build rule that builds the static library version of this C/C++ library.
   */
  private static BuildRule createStaticLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      PicType pic,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Create rules for compiling the object files.
    ImmutableList<SourcePath> objects =
        requireObjects(
            buildTarget,
            projectFilesystem,
            resolver,
            sourcePathResolver,
            ruleFinder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            pic,
            args,
            deps,
            transitiveCxxPreprocessorInputFunction,
            delegate);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic);

    if (objects.isEmpty()) {
      return new NoopBuildRule(staticTarget, projectFilesystem) {
        @Override
        public SortedSet<BuildRule> getBuildDeps() {
          return ImmutableSortedSet.of();
        }
      };
    }

    Path staticLibraryPath =
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            buildTarget,
            cxxPlatform.getFlavor(),
            pic,
            cxxPlatform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled());
    return Archive.from(
        staticTarget,
        projectFilesystem,
        resolver,
        ruleFinder,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        staticLibraryPath,
        ImmutableList.copyOf(objects),
        /* cacheable */ true);
  }

  /** @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library. */
  private static CxxLink createSharedLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      Linker.LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {
    ImmutableList.Builder<StringWithMacros> linkerFlags = ImmutableList.builder();

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform));

    linkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), cxxPlatform));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return createSharedLibrary(
        buildTarget,
        projectFilesystem,
        resolver,
        sourcePathResolver,
        ruleFinder,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        args,
        deps,
        linkerFlags.build(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getSoname(),
        args.getCxxRuntimeType(),
        linkType,
        linkableDepType,
        bundleLoader,
        blacklist,
        transitiveCxxPreprocessorInputFunction,
        delegate);
  }

  private static BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform) {

    Optional<SharedLibraryInterfaceParams> params = cxxPlatform.getSharedLibraryInterfaceParams();
    if (!params.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    CxxLink sharedLibrary =
        (CxxLink)
            resolver.requireRule(
                baseTarget.withAppendedFlavors(
                    cxxPlatform.getFlavor(), CxxLibraryDescription.Type.SHARED.getFlavor()));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params.get())
        .createSharedInterfaceLibrary(
            baseTarget.withAppendedFlavors(
                CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            resolver,
            pathResolver,
            ruleFinder,
            sharedLibrary.getSourcePathToOutput());
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
