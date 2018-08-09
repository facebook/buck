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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkType;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CxxLibraryFactory {

  private static final ImmutableSet<Flavor> FLAVORS_WITH_DEFAULT_PLATFORM =
      ImmutableSet.<Flavor>builder()
          .add(CxxCompilationDatabase.COMPILATION_DATABASE)
          .add(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)
          .addAll(CxxInferEnhancer.INFER_FLAVOR_DOMAIN.getFlavors())
          .build();

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
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
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

    Supplier<CxxPlatform> cxxPlatformOrDefaultSupplier =
        () ->
            platform.orElse(
                cxxPlatforms.getValue(args.getDefaultPlatform().orElse(defaultCxxFlavor)));
    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      CxxPlatform cxxPlatformOrDefault = cxxPlatformOrDefaultSupplier.get();
      // XXX: This needs bundleLoader for tests..
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
      // TODO(T21900763): We should be using `requireObjects` instead but those would not
      // necessarily be `CxxPreprocessAndCompile` rules (e.g., Swift in `apple_library`).
      ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
          requireCxxObjects(
              buildTarget.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              graphBuilder,
              sourcePathResolver,
              ruleFinder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatformOrDefault,
              PicType.PIC,
              args,
              cxxDeps.get(graphBuilder, cxxPlatformOrDefault),
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
          graphBuilder);
    } else if (CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      return CxxInferEnhancer.requireInferRule(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          cellRoots,
          cxxBuckConfig,
          cxxPlatformOrDefaultSupplier.get(),
          args,
          inferBuckConfig);
    } else if (type.isPresent() && !platform.isPresent()) {
      BuildTarget untypedBuildTarget = CxxLibraryDescription.getUntypedBuildTarget(buildTarget);
      switch (type.get().getValue()) {
        case EXPORTED_HEADERS:
          Optional<HeaderMode> mode = CxxLibraryDescription.HEADER_MODE.getValue(buildTarget);
          if (mode.isPresent()) {
            return createExportedHeaderSymlinkTreeBuildRule(
                untypedBuildTarget, projectFilesystem, graphBuilder, mode.get(), args);
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
              untypedBuildTarget, projectFilesystem, graphBuilder, platform.get(), args);
        case EXPORTED_HEADERS:
          return createExportedPlatformHeaderSymlinkTreeBuildRule(
              untypedBuildTarget, projectFilesystem, graphBuilder, platform.get(), args);
        case SHARED:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
              Linker.LinkType.SHARED,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              Optional.empty(),
              blacklist,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case SHARED_INTERFACE:
          return createSharedLibraryInterface(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              platform.get(),
              cxxBuckConfig.isIndependentSharedLibraryInterfaces());
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
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
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
              PicType.PDC,
              transitiveCxxPreprocessorInputFunction,
              delegate);
        case STATIC_PIC:
          return createStaticLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
              PicType.PIC,
              transitiveCxxPreprocessorInputFunction,
              delegate);
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
    return new CxxLibrary(
        buildTarget,
        projectFilesystem,
        metadataRuleParams,
        graphBuilder.getParallelizer(),
        args.getPrivateCxxDeps(),
        args.getExportedCxxDeps(),
        hasObjects.negate(),
        (input, graphBuilderInner) -> {
          ImmutableList<Arg> delegateExportedLinkerFlags =
              delegate
                  .map(
                      d ->
                          d.getAdditionalExportedLinkerFlags(buildTarget, graphBuilderInner, input))
                  .orElse(ImmutableList.of());

          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input);
          return RichStream.from(flags)
              .map(
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, graphBuilderInner, input, f))
              .concat(RichStream.from(delegateExportedLinkerFlags))
              .toImmutableList();
        },
        (input, graphBuilderInner) -> {
          ImmutableList<Arg> delegatePostExportedLinkerFlags =
              delegate
                  .map(
                      d ->
                          d.getAdditionalPostExportedLinkerFlags(
                              buildTarget, graphBuilderInner, input))
                  .orElse(ImmutableList.of());

          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedPostLinkerFlags(),
                  args.getExportedPostPlatformLinkerFlags(),
                  input);
          return RichStream.from(flags)
              .map(
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, graphBuilderInner, input, f))
              .concat(RichStream.from(delegatePostExportedLinkerFlags))
              .toImmutableList();
        },
        (cxxPlatform, ruleResolverInner, pathResolverInner, ruleFinderInner) -> {
          return getSharedLibraryNativeLinkTargetInput(
              buildTarget,
              projectFilesystem,
              ruleResolverInner,
              pathResolverInner,
              ruleFinderInner,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              args,
              cxxDeps.get(ruleResolverInner, cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(),
                  args.getExportedPlatformLinkerFlags(),
                  cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getPostLinkerFlags(), args.getPostPlatformLinkerFlags(), cxxPlatform),
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedPostLinkerFlags(),
                  args.getExportedPostPlatformLinkerFlags(),
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
        args.isReexportAllHeaderDependencies()
            .orElse(cxxBuckConfig.getDefaultReexportAllHeaderDependencies()),
        delegate);
  }

  /**
   * Calculates a platform flavor for a given target. If a target has no flavor, then calculate it
   * using other flavors.
   *
   * <p>Note that this logic should be kept in sync with {@link #createBuildRule}.
   */
  private Flavor getPlatformFlavor(BuildTarget buildTarget, HasDefaultPlatform args) {
    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultCxxPlatform().getFlavor();
    Optional<CxxPlatform> platform = cxxPlatforms.getValue(buildTarget);
    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)
        || CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(buildTarget.getFlavors())) {
      CxxPlatform cxxPlatformOrDefault =
          platform.orElse(
              cxxPlatforms.getValue(args.getDefaultPlatform().orElse(defaultCxxFlavor)));
      return cxxPlatformOrDefault.getFlavor();
    } else if (buildTarget
        .getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return platform.isPresent()
          ? platform.get().getFlavor()
          : args.getDefaultPlatform().orElse(defaultCxxFlavor);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Target %s contains unrecognized flavors: %s",
              buildTarget.getFullyQualifiedName(), buildTarget.getFlavors()));
    }
  }

  /**
   * @return an {@link Iterable} with platform dependencies that need to be resolved at parse time.
   */
  public Iterable<BuildTarget> getPlatformParseTimeDeps(
      BuildTarget buildTarget, HasDefaultPlatform args) {
    if (Sets.intersection(buildTarget.getFlavors(), FLAVORS_WITH_DEFAULT_PLATFORM).isEmpty()) {
      return CxxPlatforms.getParseTimeDeps(
          getCxxPlatformsProvider().getCxxPlatforms().getValues(buildTarget));
    } else {
      return CxxPlatforms.getParseTimeDeps(
          getCxxPlatformsProvider()
              .getCxxPlatforms()
              .getValue(getPlatformFlavor(buildTarget, args)));
    }
  }

  private static ImmutableList<SourcePath> requireObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
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
            graphBuilder,
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
        delegate.flatMap(p -> p.getObjectFilePaths(buildTarget, graphBuilder, cxxPlatform));
    pluginObjectPaths.ifPresent(paths -> builder.addAll(paths));

    return builder.build();
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireCxxObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
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
            ruleFinder,
            graphBuilder,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                buildTarget,
                graphBuilder,
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
            d.getPrivateHeaderSymlinkTree(buildTarget, graphBuilder, cxxPlatform)
                .ifPresent(h -> privateHeaderSymlinkTrees.add(h)));

    // Create rule to build the object files.
    ImmutableMultimap<CxxSource.Type, Arg> compilerFlags =
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getCompilerFlags(),
                    args.getPlatformCompilerFlags(),
                    args.getLangCompilerFlags(),
                    args.getLangPlatformCompilerFlags(),
                    cxxPlatform),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        buildTarget, cellRoots, graphBuilder, cxxPlatform, f)));
    return CxxSourceRuleFactory.of(
            projectFilesystem,
            buildTarget,
            graphBuilder,
            sourcePathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            CxxLibraryDescription.getPreprocessorInputsForBuildingLibrarySources(
                cxxBuckConfig,
                graphBuilder,
                cellRoots,
                buildTarget,
                args,
                cxxPlatform,
                deps,
                transitivePreprocessorInputs,
                privateHeaderSymlinkTrees.build()),
            compilerFlags,
            args.getPrefixHeader(),
            args.getPrecompiledHeader(),
            pic)
        .requirePreprocessAndCompileRules(
            CxxDescriptionEnhancer.parseCxxSources(
                buildTarget, graphBuilder, ruleFinder, sourcePathResolver, cxxPlatform, args));
  }

  private static NativeLinkableInput getSharedLibraryNativeLinkTargetInput(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg arg,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<StringWithMacros> exportedLinkerFlags,
      ImmutableList<StringWithMacros> postLinkerFlags,
      ImmutableList<StringWithMacros> postExportedLinkerFlags,
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
            graphBuilder,
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
                            buildTarget, cellRoots, graphBuilder, cxxPlatform, f))
                .toImmutableList())
        .addAllArgs(SourcePathArg.from(objects))
        .addAllArgs(
            RichStream.from(postLinkerFlags)
                .map(
                    f ->
                        CxxDescriptionEnhancer.toStringWithMacrosArgs(
                            buildTarget, cellRoots, graphBuilder, cxxPlatform, f))
                .toImmutableList())
        .addAllArgs(
            RichStream.from(postExportedLinkerFlags.stream())
                .map(
                    f ->
                        CxxDescriptionEnhancer.toStringWithMacrosArgs(
                            buildTarget, cellRoots, graphBuilder, cxxPlatform, f))
                .toImmutableList())
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
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      ImmutableList<StringWithMacros> linkerFlags,
      ImmutableList<StringWithMacros> postLinkerFlags,
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
            graphBuilder,
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
            .flatMap(d -> d.getNativeLinkableExportedDeps(sharedTarget, graphBuilder, cxxPlatform))
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
        graphBuilder,
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
                                sharedTarget, cellRoots, graphBuilder, cxxPlatform, f))
                    .toImmutableList())
            .addAllArgs(SourcePathArg.from(objects))
            .addAllArgs(
                RichStream.from(postLinkerFlags)
                    .map(
                        f ->
                            CxxDescriptionEnhancer.toStringWithMacrosArgs(
                                sharedTarget, cellRoots, graphBuilder, cxxPlatform, f))
                    .toImmutableList())
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
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {
    boolean shouldCreatePrivateHeaderSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        graphBuilder,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, graphBuilder, ruleFinder, pathResolver, Optional.of(cxxPlatform), args),
        HeaderVisibility.PRIVATE,
        shouldCreatePrivateHeaderSymlinks);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      HeaderMode mode,
      CxxLibraryDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        mode,
        CxxDescriptionEnhancer.parseExportedHeaders(
            buildTarget, graphBuilder, ruleFinder, pathResolver, Optional.empty(), args),
        HeaderVisibility.PUBLIC);
  }

  /** @return a {@link HeaderSymlinkTree} for the exported headers of this C/C++ library. */
  private HeaderSymlinkTree createExportedPlatformHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {
    boolean shouldCreatePublicHeaderSymlinks =
        args.getXcodePublicHeadersSymlinks().orElse(cxxPlatform.getPublicHeadersSymlinksEnabled());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        graphBuilder,
        cxxPlatform,
        CxxDescriptionEnhancer.parseExportedPlatformHeaders(
            buildTarget, graphBuilder, ruleFinder, pathResolver, cxxPlatform, args),
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
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      PicType pic,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate> delegate) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Create rules for compiling the object files.
    ImmutableList<SourcePath> objects =
        requireObjects(
            buildTarget,
            projectFilesystem,
            graphBuilder,
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
            args.getStaticLibraryBasename(),
            cxxPlatform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled());
    return Archive.from(
        staticTarget,
        projectFilesystem,
        graphBuilder,
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
      ActionGraphBuilder graphBuilder,
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

    ImmutableList.Builder<StringWithMacros> postLinkerFlags = ImmutableList.builder();

    postLinkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getPostLinkerFlags(), args.getPostPlatformLinkerFlags(), cxxPlatform));

    postLinkerFlags.addAll(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getExportedPostLinkerFlags(),
            args.getExportedPostPlatformLinkerFlags(),
            cxxPlatform));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return createSharedLibrary(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        sourcePathResolver,
        ruleFinder,
        cellRoots,
        cxxBuckConfig,
        cxxPlatform,
        args,
        deps,
        linkerFlags.build(),
        postLinkerFlags.build(),
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

  // Create a shared library interface from the shared library built by this description.
  private BuildRule createDependentSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      SharedLibraryInterfaceParams params) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Otherwise, grab the rule's original shared library and use that.
    CxxLink sharedLibrary =
        (CxxLink)
            graphBuilder.requireRule(
                baseTarget.withAppendedFlavors(
                    cxxPlatform.getFlavor(), CxxLibraryDescription.Type.SHARED.getFlavor()));

    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params)
        .createSharedInterfaceLibraryFromLibrary(
            baseTarget.withAppendedFlavors(
                CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            cxxPlatform,
            sharedLibrary.getSourcePathToOutput());
  }

  // Create a shared library interface directly from this rule's object files -- independent of the
  // the shared library built by this description.
  private BuildRule createIndependentSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      SharedLibraryInterfaceParams params) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    NativeLinkTarget linkTarget =
        (NativeLinkTarget)
            graphBuilder.requireRule(baseTarget.withoutFlavors(cxxPlatform.getFlavor()));

    NativeLinkTargetMode linkTargetMode = linkTarget.getNativeLinkTargetMode(cxxPlatform);
    Preconditions.checkArgument(linkTargetMode.getType().equals(LinkType.SHARED));

    Linker linker = cxxPlatform.getLd().resolve(graphBuilder);

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.from(
            cxxPlatform.getCompilerDebugPathSanitizer().sanitize(Optional.empty()),
            cxxPlatform.getLdflags()));

    // Add flag to link a shared library.
    argsBuilder.addAll(linker.getSharedLibFlag());

    // Add flag to embed an SONAME.
    String soname =
        linkTarget
            .getNativeLinkTargetMode(cxxPlatform)
            .getLibraryName()
            .orElse(CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(baseTarget, cxxPlatform));
    argsBuilder.addAll(StringArg.from(linker.soname(soname)));

    // Add the args for the root link target first.
    NativeLinkableInput input =
        linkTarget.getNativeLinkTargetInput(cxxPlatform, graphBuilder, pathResolver, ruleFinder);
    argsBuilder.addAll(input.getArgs());

    // Since we're linking against a dummy libomnibus, ignore undefined symbols.  Put this at the
    // end to override any user-provided settings.
    argsBuilder.addAll(StringArg.from(linker.getIgnoreUndefinedSymbolsFlags()));

    // Add all arguments needed to link in the C/C++ platform runtime.
    argsBuilder.addAll(
        StringArg.from(cxxPlatform.getRuntimeLdflags().get(Linker.LinkableDepType.SHARED)));

    // Add in additional, user-configured flags.
    argsBuilder.addAll(StringArg.from(params.getLdflags()));

    ImmutableList<Arg> args = argsBuilder.build();

    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params)
        .createSharedInterfaceLibraryFromLinkableInput(
            baseTarget.withAppendedFlavors(
                CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            graphBuilder,
            pathResolver,
            ruleFinder,
            soname,
            linker,
            args);
  }

  private BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      boolean isIndependentInterfaces) {

    Optional<SharedLibraryInterfaceParams> params = cxxPlatform.getSharedLibraryInterfaceParams();
    if (!params.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    return isIndependentInterfaces
        ? createIndependentSharedLibraryInterface(
            baseTarget, projectFilesystem, graphBuilder, cxxPlatform, params.get())
        : createDependentSharedLibraryInterface(
            baseTarget, projectFilesystem, graphBuilder, cxxPlatform, params.get());
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
