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

package com.facebook.buck.features.halide;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxBinary;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags.TranslateMacrosFunction;
import com.facebook.buck.cxx.CxxLinkAndCompileRules;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class HalideLibraryDescription
    implements DescriptionWithTargetGraph<HalideLibraryDescriptionArg>, Flavored {

  public static final Flavor HALIDE_COMPILER_FLAVOR = InternalFlavor.of("halide-compiler");
  public static final Flavor HALIDE_COMPILE_FLAVOR = InternalFlavor.of("halide-compile");

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final HalideBuckConfig halideBuckConfig;

  public HalideLibraryDescription(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      HalideBuckConfig halideBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.halideBuckConfig = halideBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return getCxxPlatformsProvider().getCxxPlatforms().containsAnyOf(flavors)
        || flavors.contains(HALIDE_COMPILE_FLAVOR)
        || flavors.contains(HALIDE_COMPILER_FLAVOR)
        || StripStyle.FLAVOR_DOMAIN.containsAnyOf(flavors);
  }

  @Override
  public Class<HalideLibraryDescriptionArg> getConstructorArgType() {
    return HalideLibraryDescriptionArg.class;
  }

  public static BuildTarget createHalideCompilerBuildTarget(BuildTarget target) {
    return target.withFlavors(HALIDE_COMPILER_FLAVOR);
  }

  public static boolean isPlatformSupported(
      HalideLibraryDescriptionArg arg, CxxPlatform cxxPlatform) {
    return !arg.getSupportedPlatformsRegex().isPresent()
        || arg.getSupportedPlatformsRegex()
            .get()
            .matcher(cxxPlatform.getFlavor().toString())
            .find();
  }

  private CxxBinary createHalideCompiler(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxPlatformsProvider cxxPlatformsProvider,
      CxxPlatform cxxPlatform,
      ImmutableSortedSet<SourceWithFlags> halideSources,
      ImmutableList<StringWithMacros> compilerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformCompilerFlags,
      ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>> langCompilerFlags,
      ImmutableList<StringWithMacros> linkerFlags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformLinkerFlags,
      ImmutableSortedSet<SourcePath> rawHeaders) {

    Optional<StripStyle> flavoredStripStyle = StripStyle.FLAVOR_DOMAIN.getValue(buildTarget);
    Optional<LinkerMapMode> flavoredLinkerMapMode =
        LinkerMapMode.FLAVOR_DOMAIN.getValue(buildTarget);
    buildTarget = CxxStrip.removeStripStyleFlavorInTarget(buildTarget, flavoredStripStyle);
    buildTarget =
        LinkerMapMode.removeLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);

    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            buildTarget,
            graphBuilder,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            halideSources,
            PatternMatchedCollection.of());

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            srcs,
            /* headers */ ImmutableMap.of(),
            params.getBuildDeps(),
            ImmutableSet.of(),
            flavoredStripStyle,
            flavoredLinkerMapMode,
            Linker.LinkableDepType.STATIC,
            CxxLinkOptions.of(),
            ImmutableList.of(),
            PatternMatchedCollection.of(),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            compilerFlags,
            langCompilerFlags,
            platformCompilerFlags,
            ImmutableMap.of(),
            Optional.empty(),
            Optional.empty(),
            linkerFlags,
            ImmutableList.of(),
            platformLinkerFlags,
            Optional.empty(),
            rawHeaders,
            Optional.empty());

    buildTarget = CxxStrip.restoreStripStyleFlavorInTarget(buildTarget, flavoredStripStyle);
    buildTarget =
        LinkerMapMode.restoreLinkerMapModeFlavorInTarget(buildTarget, flavoredLinkerMapMode);
    return new CxxBinary(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(
            BuildableSupport.getDepsCollection(cxxLinkAndCompileRules.executable, ruleFinder)),
        cxxPlatform,
        cxxLinkAndCompileRules.getBinaryRule(),
        cxxLinkAndCompileRules.executable,
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        buildTarget.withoutFlavors(cxxPlatformsProvider.getCxxPlatforms().getFlavors()),
        cxxBuckConfig.shouldCacheBinaries());
  }

  private BuildRule createHalideStaticLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform platform,
      HalideLibraryDescriptionArg args) {

    if (!isPlatformSupported(args, platform)) {
      return new NoopBuildRuleWithDeclaredAndExtraDeps(buildTarget, projectFilesystem, params);
    }

    BuildRule halideCompile =
        graphBuilder.requireRule(
            buildTarget.withFlavors(HALIDE_COMPILE_FLAVOR, platform.getFlavor()));
    BuildTarget halideCompileBuildTarget = halideCompile.getBuildTarget();

    return Archive.from(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        ruleFinder,
        platform,
        CxxDescriptionEnhancer.getStaticLibraryPath(
            projectFilesystem,
            buildTarget,
            platform.getFlavor(),
            PicType.PIC,
            Optional.empty(),
            platform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled()),
        ImmutableList.of(
            ExplicitBuildTargetSourcePath.of(
                halideCompileBuildTarget,
                HalideCompile.objectOutputPath(
                    halideCompileBuildTarget, projectFilesystem, args.getFunctionName()))),
        /* cacheable */ true);
  }

  private Optional<ImmutableList<String>> expandInvocationFlags(
      Optional<ImmutableList<String>> optionalFlags, CxxPlatform platform) {
    if (optionalFlags.isPresent()) {
      AddsToRuleKeyFunction<String, String> macroMapper =
          new TranslateMacrosFunction(
              ImmutableSortedMap.copyOf(platform.getFlagMacros()), platform);
      ImmutableList<String> flags = optionalFlags.get();
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      for (String flag : flags) {
        builder.add(macroMapper.apply(flag));
      }
      optionalFlags = Optional.of(builder.build());
    }
    return optionalFlags;
  }

  private BuildRule createHalideCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxPlatform platform,
      Optional<ImmutableList<String>> compilerInvocationFlags,
      Optional<String> functionName) {
    CxxBinary halideCompiler =
        (CxxBinary) graphBuilder.requireRule(buildTarget.withFlavors(HALIDE_COMPILER_FLAVOR));

    return new HalideCompile(
        buildTarget,
        projectFilesystem,
        params.withExtraDeps(ImmutableSortedSet.of(halideCompiler)),
        halideCompiler.getExecutableCommand(),
        halideBuckConfig.getHalideTargetForPlatform(platform),
        expandInvocationFlags(compilerInvocationFlags, platform),
        functionName);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      HalideLibraryDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProvider.getCxxPlatforms();

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(buildTarget.getFlavors());
    CxxPlatform cxxPlatform =
        cxxPlatforms.getValue(flavors).orElse(cxxPlatformsProvider.getDefaultCxxPlatform());
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (flavors.contains(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR)) {
      ImmutableMap.Builder<Path, SourcePath> headersBuilder = ImmutableMap.builder();
      BuildTarget compileTarget =
          graphBuilder
              .requireRule(buildTarget.withFlavors(HALIDE_COMPILE_FLAVOR, cxxPlatform.getFlavor()))
              .getBuildTarget();
      Path outputPath =
          HalideCompile.headerOutputPath(compileTarget, projectFilesystem, args.getFunctionName());
      headersBuilder.put(
          outputPath.getFileName(), ExplicitBuildTargetSourcePath.of(compileTarget, outputPath));
      return CxxDescriptionEnhancer.createHeaderSymlinkTree(
          buildTarget,
          projectFilesystem,
          ruleFinder,
          graphBuilder,
          cxxPlatform,
          headersBuilder.build(),
          HeaderVisibility.PUBLIC,
          true);
    } else if (flavors.contains(HALIDE_COMPILER_FLAVOR)) {
      // We always want to build the halide "compiler" for the host platform, so
      // we use the host flavor here, regardless of the flavors on the build
      // target.
      CxxPlatform hostCxxPlatform = cxxPlatforms.getValue(CxxPlatforms.getHostFlavor());
      ImmutableSortedSet<BuildTarget> compilerDeps = args.getCompilerDeps();
      return createHalideCompiler(
          buildTarget,
          projectFilesystem,
          params.withDeclaredDeps(graphBuilder.getAllRules(compilerDeps)).withoutExtraDeps(),
          graphBuilder,
          pathResolver,
          ruleFinder,
          context.getCellPathResolver(),
          cxxPlatformsProvider,
          hostCxxPlatform,
          args.getSrcs(),
          args.getCompilerFlags(),
          args.getPlatformCompilerFlags(),
          args.getLangCompilerFlags(),
          args.getLinkerFlags(),
          args.getPlatformLinkerFlags(),
          args.getRawHeaders());
    } else if (flavors.contains(CxxDescriptionEnhancer.STATIC_FLAVOR)
        || flavors.contains(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR)) {
      // Halide always output PIC, so it's output can be used for both cases.
      // See: https://github.com/halide/Halide/blob/e3c301f3/src/LLVM_Output.cpp#L152
      return createHalideStaticLibrary(
          buildTarget, projectFilesystem, params, graphBuilder, ruleFinder, cxxPlatform, args);
    } else if (flavors.contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
      throw new HumanReadableException(
          "halide_library '%s' does not support shared libraries as output", buildTarget);
    } else if (flavors.contains(HALIDE_COMPILE_FLAVOR)) {
      return createHalideCompile(
          buildTarget,
          projectFilesystem,
          params.withoutDeclaredDeps().withoutExtraDeps(),
          graphBuilder,
          cxxPlatform,
          Optional.of(args.getCompilerInvocationFlags()),
          args.getFunctionName());
    }

    return new HalideLibrary(
        buildTarget, projectFilesystem, params, graphBuilder, args.getSupportedPlatformsRegex());
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractHalideLibraryDescriptionArg extends CxxBinaryDescription.CommonArg {
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getCompilerDeps();

    @Value.NaturalOrder
    ImmutableSortedMap<String, ImmutableMap<String, String>> getConfigs();

    Optional<Pattern> getSupportedPlatformsRegex();

    ImmutableList<String> getCompilerInvocationFlags();

    Optional<String> getFunctionName();
  }
}
