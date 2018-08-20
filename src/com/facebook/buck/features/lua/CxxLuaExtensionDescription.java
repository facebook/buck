/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class CxxLuaExtensionDescription
    implements DescriptionWithTargetGraph<CxxLuaExtensionDescriptionArg>,
        ImplicitDepsInferringDescription<
            CxxLuaExtensionDescription.AbstractCxxLuaExtensionDescriptionArg>,
        VersionPropagator<CxxLuaExtensionDescriptionArg>,
        Flavored {

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;

  public CxxLuaExtensionDescription(
      ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  private String getExtensionName(BuildTarget target, CxxPlatform cxxPlatform) {
    return String.format("%s.%s", target.getShortName(), cxxPlatform.getSharedLibraryExtension());
  }

  private BuildTarget getExtensionTarget(BuildTarget target, Flavor platform) {
    return target.withAppendedFlavors(platform);
  }

  private Path getExtensionPath(
      ProjectFilesystem filesystem, BuildTarget target, CxxPlatform cxxPlatform) {
    return BuildTargetPaths.getGenPath(
            filesystem, getExtensionTarget(target, cxxPlatform.getFlavor()), "%s")
        .resolve(getExtensionName(target, cxxPlatform));
  }

  private ImmutableList<Arg> getExtensionArgs(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      LuaPlatform luaPlatform,
      CxxLuaExtensionDescriptionArg args) {

    CxxPlatform cxxPlatform = luaPlatform.getCxxPlatform();

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            buildTarget, graphBuilder, ruleFinder, pathResolver, cxxPlatform, args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            buildTarget, graphBuilder, ruleFinder, pathResolver, Optional.of(cxxPlatform), args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            buildTarget,
            projectFilesystem,
            ruleFinder,
            graphBuilder,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            true);
    ImmutableSet<BuildRule> deps = args.getCxxDeps().get(graphBuilder, cxxPlatform);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        ImmutableList.<CxxPreprocessorInput>builder()
            .add(
                luaPlatform
                    .getLuaCxxLibrary(graphBuilder)
                    .getCxxPreprocessorInput(cxxPlatform, graphBuilder))
            .addAll(
                CxxDescriptionEnhancer.collectCxxPreprocessorInput(
                    buildTarget,
                    cxxPlatform,
                    graphBuilder,
                    deps,
                    ImmutableListMultimap.copyOf(
                        Multimaps.transformValues(
                            CxxFlags.getLanguageFlagsWithMacros(
                                args.getPreprocessorFlags(),
                                args.getPlatformPreprocessorFlags(),
                                args.getLangPreprocessorFlags(),
                                args.getLangPlatformPreprocessorFlags(),
                                cxxPlatform),
                            f ->
                                CxxDescriptionEnhancer.toStringWithMacrosArgs(
                                    buildTarget, cellRoots, graphBuilder, cxxPlatform, f))),
                    ImmutableList.of(headerSymlinkTree),
                    ImmutableSet.of(),
                    CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                        cxxPlatform, graphBuilder, deps),
                    args.getRawHeaders()))
            .build();

    // Generate rule to build the object files.
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
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.of(
                projectFilesystem,
                buildTarget,
                graphBuilder,
                pathResolver,
                ruleFinder,
                cxxBuckConfig,
                cxxPlatform,
                cxxPreprocessorInput,
                compilerFlags,
                args.getPrefixHeader(),
                args.getPrecompiledHeader(),
                PicType.PIC)
            .requirePreprocessAndCompileRules(srcs);

    ImmutableList.Builder<Arg> argsBuilder = ImmutableList.builder();
    CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            args.getLinkerFlags(), args.getPlatformLinkerFlags(), cxxPlatform)
        .stream()
        .map(
            f ->
                CxxDescriptionEnhancer.toStringWithMacrosArgs(
                    buildTarget, cellRoots, graphBuilder, cxxPlatform, f))
        .forEach(argsBuilder::add);

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(picObjects.values()));

    return argsBuilder.build();
  }

  private BuildRule createExtensionBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      LuaPlatform luaPlatform,
      CxxLuaExtensionDescriptionArg args) {
    CxxPlatform cxxPlatform = luaPlatform.getCxxPlatform();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    String extensionName = getExtensionName(buildTarget, cxxPlatform);
    Path extensionPath = getExtensionPath(projectFilesystem, buildTarget, cxxPlatform);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
        pathResolver,
        ruleFinder,
        getExtensionTarget(buildTarget, cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        args.getLinkerExtraOutputs(),
        Linker.LinkableDepType.SHARED,
        CxxLinkOptions.of(),
        RichStream.from(args.getCxxDeps().get(graphBuilder, cxxPlatform))
            .filter(NativeLinkable.class)
            .concat(Stream.of(luaPlatform.getLuaCxxLibrary(graphBuilder)))
            .toImmutableList(),
        args.getCxxRuntimeType(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .setArgs(
                getExtensionArgs(
                    buildTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor()),
                    projectFilesystem,
                    graphBuilder,
                    pathResolver,
                    ruleFinder,
                    cellRoots,
                    luaPlatform,
                    args))
            .build(),
        Optional.empty(),
        cellRoots);
  }

  @Override
  public Class<CxxLuaExtensionDescriptionArg> getConstructorArgType() {
    return CxxLuaExtensionDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxLuaExtensionDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    FlavorDomain<LuaPlatform> luaPlatforms = getLuaPlatformsProvider().getLuaPlatforms();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    Optional<Map.Entry<Flavor, LuaPlatform>> platform = luaPlatforms.getFlavorAndValue(buildTarget);

    // If a C/C++ platform is specified, then build an extension with it.
    if (platform.isPresent()) {
      return createExtensionBuildRule(
          buildTarget, projectFilesystem, graphBuilder, cellRoots, platform.get().getValue(), args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    return new CxxLuaExtension(buildTarget, projectFilesystem, params) {

      @Override
      public String getModule(CxxPlatform cxxPlatform) {
        String baseModule = LuaUtil.getBaseModule(buildTarget, args.getBaseModule());
        String name = getExtensionName(buildTarget, cxxPlatform);
        return baseModule.isEmpty() ? name : baseModule + File.separator + name;
      }

      @Override
      public SourcePath getExtension(CxxPlatform cxxPlatform) {
        BuildRule rule =
            graphBuilder.requireRule(getBuildTarget().withAppendedFlavors(cxxPlatform.getFlavor()));
        return Preconditions.checkNotNull(rule.getSourcePathToOutput());
      }

      @Override
      public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
        return NativeLinkTargetMode.library();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
          CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
        return RichStream.from(args.getCxxDeps().get(graphBuilder, cxxPlatform))
            .filter(NativeLinkable.class)
            .toImmutableList();
      }

      @Override
      public NativeLinkableInput getNativeLinkTargetInput(
          CxxPlatform cxxPlatform,
          ActionGraphBuilder graphBuilder,
          SourcePathResolver pathResolver,
          SourcePathRuleFinder ruleFinder) {
        return NativeLinkableInput.builder()
            .addAllArgs(
                getExtensionArgs(
                    buildTarget,
                    projectFilesystem,
                    graphBuilder,
                    pathResolver,
                    ruleFinder,
                    cellRoots,
                    luaPlatforms.getValue(cxxPlatform.getFlavor()),
                    args))
            .addAllFrameworks(args.getFrameworks())
            .build();
      }

      @Override
      public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
        return Optional.empty();
      }
    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractCxxLuaExtensionDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    for (LuaPlatform luaPlatform : getLuaPlatformsProvider().getLuaPlatforms().getValues()) {

      // Add deps from lua C/C++ library.
      Optionals.addIfPresent(luaPlatform.getLuaCxxLibraryTarget(), extraDepsBuilder);

      // Get any parse time deps from the C/C++ platforms.
      targetGraphOnlyDepsBuilder.addAll(
          CxxPlatforms.getParseTimeDeps(luaPlatform.getCxxPlatform()));
    }
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(getLuaPlatformsProvider().getLuaPlatforms()));
  }

  private LuaPlatformsProvider getLuaPlatformsProvider() {
    return toolchainProvider.getByName(
        LuaPlatformsProvider.DEFAULT_NAME, LuaPlatformsProvider.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxLuaExtensionDescriptionArg extends CxxConstructorArg {
    Optional<String> getBaseModule();
  }
}
