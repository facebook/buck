/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.Tools;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linker.LinkType;
import com.facebook.buck.cxx.toolchain.nativelink.LinkableListFilter;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CxxLibraryFactory {
  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public CxxLibraryFactory(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  public BuildRule createBuildRule(
      TargetGraph targetGraph,
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
      CxxLibraryDescriptionDelegate delegate,
      CxxConditionalLinkStrategyFactory linkStrategyFactory,
      CxxDebugSymbolLinkStrategyFactory debugSymbolLinkStrategyFactory) {

    CxxPlatformsProvider cxxPlatformsProvider =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration());
    Flavor defaultCxxFlavor = cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor();

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, CxxLibraryDescription.Type>> type =
        CxxLibraryDescription.getLibType(buildTarget);
    Optional<CxxPlatform> platform =
        cxxPlatformsProvider
            .getUnresolvedCxxPlatforms()
            .getValue(buildTarget)
            .map(
                unresolved ->
                    unresolved.resolve(graphBuilder, buildTarget.getTargetConfiguration()));
    CxxDeps cxxDeps = CxxDeps.builder().addDeps(args.getCxxDeps()).addDeps(extraDeps).build();

    Supplier<CxxPlatform> cxxPlatformOrDefaultSupplier =
        () ->
            platform.orElse(
                cxxPlatformsProvider
                    .getUnresolvedCxxPlatforms()
                    .getValue(args.getDefaultPlatform().orElse(defaultCxxFlavor))
                    .resolve(graphBuilder, buildTarget.getTargetConfiguration()));

    if (buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      CxxPlatform cxxPlatformOrDefault = cxxPlatformOrDefaultSupplier.get();
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> configuredDelegate =
          delegate.requireDelegate(buildTarget, cxxPlatformOrDefault, graphBuilder);

      // XXX: This needs bundleLoader for tests..
      // TODO(T21900763): We should be using `requireObjects` instead but those would not
      // necessarily be `CxxPreprocessAndCompile` rules (e.g., Swift in `apple_library`).
      ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
          requireCxxObjects(
              buildTarget.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatformOrDefault,
              cxxPlatformOrDefault.getPicTypeForSharedLinking(),
              args,
              cxxDeps.get(graphBuilder, cxxPlatformOrDefault),
              transitiveCxxPreprocessorInputFunction,
              configuredDelegate);

      ImmutableList<BuildRule> dbRules =
          configuredDelegate.map(d -> d.getCompilationDatabaseRules()).orElse(ImmutableList.of());

      return CxxCompilationDatabase.createCompilationDatabase(
          buildTarget, projectFilesystem, objects.keySet(), dbRules);
    } else if (buildTarget
        .getFlavors()
        .contains(CxxDiagnosticsEnhancer.DIAGNOSTIC_AGGREGATION_FLAVOR)) {

      CxxPlatform cxxPlatformOrDefault = cxxPlatformOrDefaultSupplier.get();
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> configuredDelegate =
          delegate.requireDelegate(buildTarget, cxxPlatformOrDefault, graphBuilder);

      BuildTarget targetWithoutDiagnosticsFlavor =
          buildTarget.withoutFlavors(CxxDiagnosticsEnhancer.DIAGNOSTIC_AGGREGATION_FLAVOR);
      CxxSourceRuleFactory sourceRuleFactory =
          createSourceRuleFactory(
              targetWithoutDiagnosticsFlavor,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatformOrDefault,
              cxxPlatformOrDefault.getPicTypeForSharedLinking(),
              args,
              cxxDeps.get(graphBuilder, cxxPlatformOrDefault),
              transitiveCxxPreprocessorInputFunction,
              configuredDelegate);

      ImmutableMap.Builder<String, Tool> toolsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, SourcePath> diagnosticPair : args.getDiagnostics().entrySet()) {
        Tool tool = Tools.resolveTool(diagnosticPair.getValue(), graphBuilder);
        toolsBuilder.put(diagnosticPair.getKey(), tool);
      }

      ImmutableMap<String, CxxSource> sources =
          CxxDescriptionEnhancer.parseCxxSources(
              targetWithoutDiagnosticsFlavor, cellRoots, graphBuilder, cxxPlatformOrDefault, args);
      ImmutableSortedSet<SourcePath> extractionRuleInputs =
          sourceRuleFactory
              .requireDiagnosticExtractionBuildRules(
                  sources, toolsBuilder.build(), downwardApiConfig.isEnabledForCxx())
              .stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

      return new CxxDiagnosticAggregationRule(
          buildTarget, projectFilesystem, graphBuilder, extractionRuleInputs);
    } else if (buildTarget.getFlavors().contains(CxxLinkGroupMapDatabase.LINK_GROUP_MAP_DATABASE)) {
      ImmutableList<BuildTarget> targets =
          getFilteredLinkableTargets(
              targetGraph,
              buildTarget,
              graphBuilder,
              args,
              linkableDepType,
              blacklist,
              delegate,
              platform,
              cxxDeps);
      return new CxxLinkGroupMapDatabase(buildTarget, projectFilesystem, graphBuilder, targets);
    } else if (buildTarget.getFlavors().contains(CxxFocusedDebugTargets.FOCUSED_DEBUG_TARGETS)) {
      ImmutableList<BuildTarget> targets =
          getFilteredLinkableTargets(
              targetGraph,
              buildTarget,
              graphBuilder,
              args,
              linkableDepType,
              blacklist,
              delegate,
              platform,
              cxxDeps);
      Optional<SourcePath> focusedListTargetPath =
          args.getFocusedListTarget()
              .map(graphBuilder::requireRule)
              .map(BuildRule::getSourcePathToOutput);
      return new CxxFocusedDebugTargets(
          buildTarget, projectFilesystem, graphBuilder, focusedListTargetPath, targets);
    } else if (buildTarget
        .getFlavors()
        .contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          platform.isPresent()
              ? buildTarget
              : buildTarget.withAppendedFlavors(args.getDefaultPlatform().orElse(defaultCxxFlavor)),
          projectFilesystem,
          graphBuilder);
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
          downwardApiConfig,
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
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> configuredDelegate =
          delegate.requireDelegate(buildTarget, platform.get(), graphBuilder);

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
              downwardApiConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
              Linker.LinkType.SHARED,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              makeLinkableListFilter(args, targetGraph),
              Optional.empty(),
              blacklist,
              transitiveCxxPreprocessorInputFunction,
              configuredDelegate,
              linkStrategyFactory,
              debugSymbolLinkStrategyFactory);
        case SHARED_INTERFACE:
          return createSharedLibraryInterface(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              platform.get(),
              cxxBuckConfig.isIndependentSharedLibraryInterfaces(),
              downwardApiConfig.isEnabledForCxx());
        case MACH_O_BUNDLE:
          return createSharedLibraryBuildRule(
              untypedBuildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              downwardApiConfig,
              platform.get(),
              args,
              cxxDeps.get(graphBuilder, platform.get()),
              Linker.LinkType.MACH_O_BUNDLE,
              linkableDepType.orElse(Linker.LinkableDepType.SHARED),
              makeLinkableListFilter(args, targetGraph),
              bundleLoader,
              blacklist,
              transitiveCxxPreprocessorInputFunction,
              configuredDelegate,
              linkStrategyFactory,
              debugSymbolLinkStrategyFactory);
        case STATIC:
          {
            Optional<StripStyle> stripStyle = StripStyle.FLAVOR_DOMAIN.getValue(untypedBuildTarget);
            if (stripStyle.isPresent()) {
              Preconditions.checkState(extraDeps.isEmpty());
              untypedBuildTarget = untypedBuildTarget.withoutFlavors(stripStyle.get().getFlavor());
            }
            return createStaticLibraryBuildRule(
                untypedBuildTarget,
                projectFilesystem,
                graphBuilder,
                cellRoots,
                cxxBuckConfig,
                downwardApiConfig,
                platform.get(),
                args,
                cxxDeps.get(graphBuilder, platform.get()),
                PicType.PDC,
                transitiveCxxPreprocessorInputFunction,
                configuredDelegate,
                stripStyle);
          }
        case STATIC_PIC:
          {
            Optional<StripStyle> stripStyle = StripStyle.FLAVOR_DOMAIN.getValue(untypedBuildTarget);
            if (stripStyle.isPresent()) {
              Preconditions.checkState(extraDeps.isEmpty());
              untypedBuildTarget = untypedBuildTarget.withoutFlavors(stripStyle.get().getFlavor());
            }
            return createStaticLibraryBuildRule(
                untypedBuildTarget,
                projectFilesystem,
                graphBuilder,
                cellRoots,
                cxxBuckConfig,
                downwardApiConfig,
                platform.get(),
                args,
                cxxDeps.get(graphBuilder, platform.get()),
                PicType.PIC,
                transitiveCxxPreprocessorInputFunction,
                configuredDelegate,
                stripStyle);
          }
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
    return new CxxLibraryGroup(
        buildTarget,
        projectFilesystem,
        metadataRuleParams.getDeclaredDeps(),
        args.getPrivateCxxDeps(),
        args.getExportedCxxDeps(),
        hasObjects.negate(),
        (input, graphBuilderInner) -> {
          ImmutableList<Arg> delegateExportedLinkerFlags =
              delegate
                  .requireDelegate(buildTarget, input, graphBuilderInner)
                  .map(d -> d.getAdditionalExportedLinkerFlags())
                  .orElse(ImmutableList.of());

          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input);
          return RichStream.from(flags)
              .map(
                  CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                          buildTarget, cellRoots, graphBuilderInner, input)
                      ::convert)
              .concat(RichStream.from(delegateExportedLinkerFlags))
              .toImmutableList();
        },
        (input, graphBuilderInner) -> {
          ImmutableList<Arg> delegatePostExportedLinkerFlags = ImmutableList.of();
          ImmutableList<StringWithMacros> flags =
              CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                  args.getExportedPostLinkerFlags(),
                  args.getExportedPostPlatformLinkerFlags(),
                  input);
          return RichStream.from(flags)
              .map(
                  CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                          buildTarget, cellRoots, graphBuilderInner, input)
                      ::convert)
              .concat(RichStream.from(delegatePostExportedLinkerFlags))
              .toImmutableList();
        },
        (cxxPlatform,
            ruleResolverInner,
            pathResolverInner,
            includePrivateLinkerFlags,
            preferStripped) ->
            getSharedLibraryNativeLinkTargetInput(
                buildTarget,
                projectFilesystem,
                ruleResolverInner,
                cellRoots,
                cxxBuckConfig,
                downwardApiConfig,
                cxxPlatform,
                args,
                cxxDeps.get(ruleResolverInner, cxxPlatform),
                includePrivateLinkerFlags
                    ? CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                        args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform)
                    : ImmutableList.of(),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.getExportedLinkerFlags(),
                    args.getExportedPlatformLinkerFlags(),
                    cxxPlatform),
                includePrivateLinkerFlags
                    ? CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                        args.getPostLinkerFlags(), args.getPostPlatformLinkerFlags(), cxxPlatform)
                    : ImmutableList.of(),
                CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                    args.getExportedPostLinkerFlags(),
                    args.getExportedPostPlatformLinkerFlags(),
                    cxxPlatform),
                args.getFrameworks(),
                args.getLibraries(),
                transitiveCxxPreprocessorInputFunction,
                delegate.requireDelegate(buildTarget, cxxPlatform, ruleResolverInner),
                preferStripped ? Optional.of(StripStyle.DEBUGGING_SYMBOLS) : Optional.empty()),
        args.getSupportedPlatformsRegex(),
        args.getFrameworks(),
        args.getLibraries(),
        args.getForceStatic().orElse(false)
            ? NativeLinkableGroup.Linkage.STATIC
            : args.getPreferredLinkage().orElse(NativeLinkableGroup.Linkage.ANY),
        args.getIncludeInAndroidMergeMapOutput(),
        args.getLinkWhole().orElse(false),
        args.getSoname(),
        args.getTests(),
        args.getCanBeAsset().orElse(false),
        !buildTarget
            .getFlavors()
            .contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
        args.isReexportAllHeaderDependencies()
            .orElse(cxxBuckConfig.getDefaultReexportAllHeaderDependencies()),
        args.getSupportsMergedLinking().orElse(true),
        args.getUseArchive(),
        CxxResourceUtils.fullyQualify(
            buildTarget,
            args.getHeaderNamespace(),
            args.getResources()
                .toNameMap(buildTarget, graphBuilder.getSourcePathResolver(), "resources")),
        delegate);
  }

  private ImmutableList<BuildTarget> getFilteredLinkableTargets(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxLibraryDescriptionArg args,
      Optional<Linker.LinkableDepType> linkableDepType,
      ImmutableSet<BuildTarget> blacklist,
      CxxLibraryDescriptionDelegate delegate,
      Optional<CxxPlatform> platform,
      CxxDeps cxxDeps) {
    Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> configuredDelegate =
        delegate.requireDelegate(buildTarget, platform.get(), graphBuilder);
    ImmutableList<NativeLinkableGroup> delegateNativeLinkableGroups =
        configuredDelegate
            .flatMap(d -> d.getNativeLinkableExportedDeps())
            .orElse(ImmutableList.of());
    ImmutableList<NativeLinkable> allNativeLinkables =
        RichStream.from(cxxDeps.get(graphBuilder, platform.get()))
            .filter(NativeLinkableGroup.class)
            .concat(RichStream.from(delegateNativeLinkableGroups))
            .map(g -> g.getNativeLinkable(platform.get(), graphBuilder))
            .toImmutableList();
    ImmutableList<BuildTarget> targets =
        ImmutableList.copyOf(
            Collections2.transform(
                CxxLinkableEnhancer.getTransitiveNativeLinkablesForLinkableDeps(
                    graphBuilder,
                    linkableDepType.orElse(Linker.LinkableDepType.STATIC),
                    makeLinkableListFilter(args, targetGraph),
                    allNativeLinkables,
                    blacklist),
                linkable -> linkable.getBuildTarget()));
    return targets;
  }

  private Optional<LinkableListFilter> makeLinkableListFilter(
      CxxLibraryDescriptionArg args, TargetGraph targetGraph) {
    return LinkableListFilterFactory.from(args, targetGraph);
  }

  public Iterable<BuildTarget> getPlatformParseTimeDeps(TargetConfiguration targetConfiguration) {
    return CxxPlatformParseTimeDeps.getPlatformParseTimeDeps(
        toolchainProvider, targetConfiguration);
  }

  static ImmutableList<SourcePath> requireObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate) {

    // TODO(T21900747): Fix dependence on order of object paths
    ImmutableList.Builder<SourcePath> builder = ImmutableList.builder();
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> cxxObjects =
        requireCxxObjects(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            downwardApiConfig,
            cxxPlatform,
            pic,
            args,
            deps,
            transitivePreprocessorInputs,
            delegate);

    builder.addAll(cxxObjects.values());

    Optional<ImmutableList<SourcePath>> pluginObjectPaths =
        delegate.map(p -> p.getObjectFilePaths());
    pluginObjectPaths.ifPresent(builder::addAll);

    return builder.build();
  }

  private static ImmutableMap<CxxPreprocessAndCompile, SourcePath> requireCxxObjects(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate) {
    CxxSourceRuleFactory sourceRuleFactory =
        createSourceRuleFactory(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            downwardApiConfig,
            cxxPlatform,
            pic,
            args,
            deps,
            transitivePreprocessorInputs,
            delegate);
    return sourceRuleFactory.requirePreprocessAndCompileRules(
        CxxDescriptionEnhancer.parseCxxSources(
            buildTarget, cellRoots, graphBuilder, cxxPlatform, args));
  }

  private static CxxSourceRuleFactory createSourceRuleFactory(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      PicType pic,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate) {

    boolean shouldCreatePrivateHeadersSymlinks =
        args.getXcodePrivateHeadersSymlinks()
            .orElse(cxxPlatform.getPrivateHeadersSymlinksEnabled());

    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cxxPlatform,
            CxxDescriptionEnhancer.parseHeaders(
                buildTarget, graphBuilder, projectFilesystem, Optional.of(cxxPlatform), args),
            HeaderVisibility.PRIVATE,
            shouldCreatePrivateHeadersSymlinks);

    ImmutableList.Builder<HeaderSymlinkTree> privateHeaderSymlinkTrees = ImmutableList.builder();
    privateHeaderSymlinkTrees.add(headerSymlinkTree);
    delegate.ifPresent(
        d -> d.getPrivateHeaderSymlinkTree().ifPresent(privateHeaderSymlinkTrees::add));

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
                CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                        buildTarget, cellRoots, graphBuilder, cxxPlatform)
                    ::convert));
    return CxxSourceRuleFactory.of(
        projectFilesystem,
        buildTarget,
        graphBuilder,
        graphBuilder.getSourcePathResolver(),
        cxxBuckConfig,
        downwardApiConfig,
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
            privateHeaderSymlinkTrees.build(),
            projectFilesystem),
        compilerFlags,
        args.getPrefixHeader(),
        args.getPrecompiledHeader(),
        pic);
  }

  private static NativeLinkableInput getSharedLibraryNativeLinkTargetInput(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
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
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate,
      Optional<StripStyle> stripStyle) {

    StringWithMacrosConverter macrosConverter =
        CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
            buildTarget, cellRoots, graphBuilder, cxxPlatform);

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects;
    if (!stripStyle.isPresent()) {
      objects =
          requireObjects(
              buildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatform,
              cxxPlatform.getPicTypeForSharedLinking(),
              arg,
              deps,
              transitiveCxxPreprocessorInputFunction,
              delegate);
    } else {
      Preconditions.checkState(!delegate.isPresent());
      objects =
          CxxLibraryMetadataFactory.requireObjects(
              buildTarget,
              graphBuilder,
              cxxPlatform,
              cxxPlatform.getPicTypeForSharedLinking(),
              stripStyle);
    }

    return NativeLinkableInput.builder()
        .addAllArgs(
            RichStream.from(linkerFlags)
                .concat(exportedLinkerFlags.stream())
                .map(macrosConverter::convert)
                .toImmutableList())
        .addAllArgs(SourcePathArg.from(objects))
        .addAllArgs(
            RichStream.from(postLinkerFlags).map(macrosConverter::convert).toImmutableList())
        .addAllArgs(
            RichStream.from(postExportedLinkerFlags.stream())
                .map(macrosConverter::convert)
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
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
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
      Optional<LinkableListFilter> linkableListFilter,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate,
      CxxConditionalLinkStrategyFactory linkStrategyFactory,
      CxxDebugSymbolLinkStrategyFactory debugSymbolLinkStrategyFactory) {
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
            cellRoots,
            cxxBuckConfig,
            downwardApiConfig,
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

    StringWithMacrosConverter macrosConverter =
        CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
            sharedTarget, cellRoots, graphBuilder, cxxPlatform);

    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            soname, buildTargetMaybeWithLinkerMapMode, cxxPlatform, projectFilesystem);
    RelPath sharedLibraryPath =
        CxxDescriptionEnhancer.getSharedLibraryPath(
            projectFilesystem, sharedTarget, sharedLibrarySoname);

    ImmutableList<NativeLinkableGroup> delegateNativeLinkableGroups =
        delegate.flatMap(d -> d.getNativeLinkableExportedDeps()).orElse(ImmutableList.of());

    ImmutableList<NativeLinkable> allNativeLinkables =
        RichStream.from(deps)
            .filter(NativeLinkableGroup.class)
            .concat(RichStream.from(delegateNativeLinkableGroups))
            .map(g -> g.getNativeLinkable(cxxPlatform, graphBuilder))
            .toImmutableList();

    CxxLinkOptions linkOptions =
        CxxLinkOptions.of(
            args.getThinLto(),
            args.getFatLto()
            );
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        downwardApiConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
        sharedTarget,
        linkType,
        Optional.of(sharedLibrarySoname),
        sharedLibraryPath.getPath(),
        args.getLinkerExtraOutputs(),
        linkableDepType,
        linkableListFilter,
        linkOptions,
        allNativeLinkables,
        cxxRuntimeType,
        bundleLoader,
        blacklist,
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(
                RichStream.from(linkerFlags).map(macrosConverter::convert).toImmutableList())
            .addAllArgs(SourcePathArg.from(objects))
            .addAllArgs(
                RichStream.from(postLinkerFlags).map(macrosConverter::convert).toImmutableList())
            .setFrameworks(frameworks)
            .setLibraries(libraries)
            .build(),
        Optional.empty(),
        cellRoots,
        linkStrategyFactory,
        debugSymbolLinkStrategyFactory,
        false);
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
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        cxxPlatform,
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, graphBuilder, projectFilesystem, Optional.of(cxxPlatform), args),
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
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        mode,
        CxxDescriptionEnhancer.parseExportedHeaders(
            buildTarget, graphBuilder, projectFilesystem, Optional.empty(), args),
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
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        cxxPlatform,
        CxxDescriptionEnhancer.parseExportedPlatformHeaders(
            buildTarget, graphBuilder, projectFilesystem, cxxPlatform, args),
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
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      PicType pic,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate,
      Optional<StripStyle> stripStyle) {

    ImmutableList<SourcePath> objects;
    if (!stripStyle.isPresent()) {
      objects =
          requireObjects(
              buildTarget,
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              downwardApiConfig,
              cxxPlatform,
              pic,
              args,
              deps,
              transitiveCxxPreprocessorInputFunction,
              delegate);
    } else {
      Preconditions.checkState(!delegate.isPresent());
      objects =
          CxxLibraryMetadataFactory.requireObjects(
              buildTarget, graphBuilder, cxxPlatform, pic, stripStyle);
    }

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic, stripStyle);

    if (objects.isEmpty()) {
      return new NoopBuildRule(staticTarget, projectFilesystem);
    }

    String staticLibraryName =
        CxxDescriptionEnhancer.getStaticLibraryName(
            buildTarget,
            args.getStaticLibraryBasename(),
            cxxPlatform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled());
    return Archive.from(
        staticTarget,
        projectFilesystem,
        graphBuilder,
        cxxPlatform,
        staticLibraryName,
        ImmutableList.copyOf(objects),
        downwardApiConfig.isEnabledForCxx());
  }

  /** @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library. */
  private static CxxLink createSharedLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args,
      ImmutableSet<BuildRule> deps,
      LinkType linkType,
      Linker.LinkableDepType linkableDepType,
      Optional<LinkableListFilter> linkableListFilter,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist,
      CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction
          transitiveCxxPreprocessorInputFunction,
      Optional<CxxLibraryDescriptionDelegate.ConfiguredDelegate> delegate,
      CxxConditionalLinkStrategyFactory linkStrategyFactory,
      CxxDebugSymbolLinkStrategyFactory debugSymbolLinkStrategyFactory) {
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

    return createSharedLibrary(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        cellRoots,
        cxxBuckConfig,
        downwardApiConfig,
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
        linkableListFilter,
        bundleLoader,
        blacklist,
        transitiveCxxPreprocessorInputFunction,
        delegate,
        linkStrategyFactory,
        debugSymbolLinkStrategyFactory);
  }

  // Create a shared library interface from the shared library built by this description.
  private BuildRule createDependentSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      SharedLibraryInterfaceParams params) {

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
            cxxPlatform,
            sharedLibrary.getSourcePathToOutput(),
            downwardApiConfig.isEnabledForCxx());
  }

  // Create a shared library interface directly from this rule's object files -- independent of the
  // the shared library built by this description.
  private BuildRule createIndependentSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      SharedLibraryInterfaceParams params,
      boolean withDownwardApi) {

    NativeLinkTarget linkTarget =
        ((NativeLinkTargetGroup)
                graphBuilder.requireRule(baseTarget.withoutFlavors(cxxPlatform.getFlavor())))
            .getTargetForPlatform(cxxPlatform, true, false);

    NativeLinkTargetMode linkTargetMode = linkTarget.getNativeLinkTargetMode();
    Preconditions.checkArgument(linkTargetMode.getType().equals(LinkType.SHARED));

    Linker linker = cxxPlatform.getLd().resolve(graphBuilder, baseTarget.getTargetConfiguration());

    // Build up the arguments to pass to the linker.
    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();

    // Pass any platform specific or extra linker flags.
    argsBuilder.addAll(
        SanitizedArg.fromArgs(
            cxxPlatform.getCompilerDebugPathSanitizer().sanitizer(Optional.empty()),
            cxxPlatform.getLdflags()));

    // Add flag to link a shared library.
    argsBuilder.addAll(linker.getSharedLibFlag());

    // Add flag to embed an SONAME.
    String soname =
        linkTarget
            .getNativeLinkTargetMode()
            .getLibraryName()
            .orElse(
                CxxDescriptionEnhancer.getDefaultSharedLibrarySoname(
                    baseTarget, cxxPlatform, projectFilesystem));
    argsBuilder.addAll(StringArg.from(linker.soname(soname)));

    // Add the args for the root link target first.
    NativeLinkableInput input =
        linkTarget.getNativeLinkTargetInput(graphBuilder, graphBuilder.getSourcePathResolver());
    argsBuilder.addAll(input.getArgs());

    // Since we're linking against a dummy libomnibus, ignore undefined symbols.  Put this at the
    // end to override any user-provided settings.
    argsBuilder.addAll(StringArg.from(linker.getIgnoreUndefinedSymbolsFlags()));

    // Add all arguments needed to link in the C/C++ platform runtime.
    argsBuilder.addAll(cxxPlatform.getRuntimeLdflags().get(Linker.LinkableDepType.SHARED));

    // Add in additional, user-configured flags.
    argsBuilder.addAll(StringArg.from(params.getLdflags()));

    ImmutableList<Arg> args = argsBuilder.build();

    return SharedLibraryInterfaceFactoryResolver.resolveFactory(params)
        .createSharedInterfaceLibraryFromLinkableInput(
            baseTarget.withAppendedFlavors(
                CxxLibraryDescription.Type.SHARED_INTERFACE.getFlavor(), cxxPlatform.getFlavor()),
            projectFilesystem,
            graphBuilder,
            soname,
            linker,
            args,
            withDownwardApi);
  }

  private BuildRule createSharedLibraryInterface(
      BuildTarget baseTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      boolean isIndependentInterfaces,
      boolean withDownwardApi) {

    Optional<SharedLibraryInterfaceParams> params = cxxPlatform.getSharedLibraryInterfaceParams();
    if (!params.isPresent()) {
      throw new HumanReadableException(
          "%s: C/C++ platform %s does not support shared library interfaces",
          baseTarget, cxxPlatform.getFlavor());
    }

    return isIndependentInterfaces
        ? createIndependentSharedLibraryInterface(
            baseTarget, projectFilesystem, graphBuilder, cxxPlatform, params.get(), withDownwardApi)
        : createDependentSharedLibraryInterface(
            baseTarget, projectFilesystem, graphBuilder, cxxPlatform, params.get());
  }

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }
}
