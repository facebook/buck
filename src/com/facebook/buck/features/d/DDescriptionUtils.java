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

package com.facebook.buck.features.d;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Utility functions for use in D Descriptions. */
abstract class DDescriptionUtils {

  public static final Flavor SOURCE_LINK_TREE = InternalFlavor.of("source-link-tree");

  /**
   * Creates a BuildTarget, based on an existing build target, but flavored with a CxxPlatform and
   * an additional flavor created by combining a prefix and an output file name.
   *
   * @param existingTarget the existing target
   * @param flavorPrefix prefix to be used for added flavor
   * @param fileName filename to be used for added flavor
   * @param cxxPlatform the C++ platform to compile for
   * @return the new BuildTarget
   */
  public static BuildTarget createBuildTargetForFile(
      BuildTarget existingTarget, String flavorPrefix, String fileName, CxxPlatform cxxPlatform) {
    return existingTarget.withAppendedFlavors(
        cxxPlatform.getFlavor(),
        InternalFlavor.of(flavorPrefix + Flavor.replaceInvalidCharacters(fileName)));
  }

  /**
   * Creates a new BuildTarget, based on an existing target, for a file to be compiled.
   *
   * @param existingTarget the existing target
   * @param src the source file to be compiled
   * @param cxxPlatform the C++ platform to compile the file for
   * @return a BuildTarget to compile a D source file to an object file
   */
  public static BuildTarget createDCompileBuildTarget(
      BuildTarget existingTarget, String src, CxxPlatform cxxPlatform) {
    return createBuildTargetForFile(
        existingTarget, "compile-", DCompileStep.getObjectNameForSourceName(src), cxxPlatform);
  }

  /**
   * Creates a {@link NativeLinkable} using sources compiled by the D compiler.
   *
   * @param cellPathResolver
   * @param params build parameters for the build target
   * @param graphBuilder graphBuilder for build rules
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @param compilerFlags flags to pass to the compiler
   * @param sources source files to compile
   * @return the new build rule
   */
  public static CxxLink createNativeLinkable(
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      ImmutableList<String> compilerFlags,
      SourceSortedSet sources,
      ImmutableList<String> linkerFlags,
      DIncludes includes) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    ImmutableList<SourcePath> sourcePaths =
        sourcePathsForCompiledSources(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            sourcePathResolver,
            ruleFinder,
            cxxPlatform,
            dBuckConfig,
            compilerFlags,
            sources,
            includes);

    // Return a rule to link the .o for the binary together with its
    // dependencies.
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        projectFilesystem,
        graphBuilder,
        sourcePathResolver,
        ruleFinder,
        buildTarget,
        Linker.LinkType.EXECUTABLE,
        Optional.empty(),
        BuildTargetPaths.getGenPath(
            projectFilesystem, buildTarget, "%s/" + buildTarget.getShortName()),
        ImmutableList.of(),
        Linker.LinkableDepType.STATIC,
        CxxLinkOptions.of(),
        FluentIterable.from(params.getBuildDeps()).filter(NativeLinkable.class),
        /* cxxRuntimeType */ Optional.empty(),
        /* bundleLoader */ Optional.empty(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
            .addAllArgs(StringArg.from(dBuckConfig.getLinkerFlags()))
            .addAllArgs(StringArg.from(linkerFlags))
            .addAllArgs(SourcePathArg.from(sourcePaths))
            .build(),
        Optional.empty(),
        cellPathResolver);
  }

  public static BuildTarget getSymlinkTreeTarget(BuildTarget baseTarget) {
    return baseTarget.withAppendedFlavors(SOURCE_LINK_TREE);
  }

  static UnresolvedCxxPlatform getUnresolvedCxxPlatform(
      ToolchainProvider toolchainProvider, DBuckConfig dBuckConfig) {
    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    return dBuckConfig
        .getDefaultCxxPlatform()
        .map(InternalFlavor::of)
        .map(flavor -> cxxPlatformsProviderFactory.getUnresolvedCxxPlatforms().getValue(flavor))
        .orElse(cxxPlatformsProviderFactory.getDefaultUnresolvedCxxPlatform());
  }

  static CxxPlatform getCxxPlatform(
      BuildRuleResolver resolver, ToolchainProvider toolchainProvider, DBuckConfig dBuckConfig) {
    return getUnresolvedCxxPlatform(toolchainProvider, dBuckConfig).resolve(resolver);
  }

  public static SymlinkTree createSourceSymlinkTree(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      SourceSortedSet sources) {
    Preconditions.checkState(target.getFlavors().contains(SOURCE_LINK_TREE));
    return new SymlinkTree(
        "d_src",
        target,
        projectFilesystem,
        BuildTargetPaths.getGenPath(projectFilesystem, target, "%s"),
        MoreMaps.transformKeys(
            sources.toNameMap(target, pathResolver, "srcs"),
            MorePaths.toPathFn(projectFilesystem.getRootPath().getFileSystem())),
        ImmutableMultimap.of(),
        ruleFinder);
  }

  private static ImmutableMap<BuildTarget, DLibrary> getTransitiveDLibraryRules(
      Iterable<? extends BuildRule> inputs) {
    ImmutableMap.Builder<BuildTarget, DLibrary> libraries = ImmutableMap.builder();
    new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        if (rule instanceof DLibrary) {
          libraries.put(rule.getBuildTarget(), (DLibrary) rule);
          return rule.getBuildDeps();
        }
        return ImmutableSet.of();
      }
    }.start();
    return libraries.build();
  }

  /**
   * Ensures that a DCompileBuildRule exists for the given target, creating a DCompileBuildRule if
   * neccesary.
   *
   * @param baseParams build parameters for the rule
   * @param graphBuilder BuildRuleResolver the rule should be in
   * @param src the source file to be compiled
   * @param compilerFlags flags to pass to the compiler
   * @param compileTarget the target the rule should be for
   * @param dBuckConfig the Buck configuration for D
   * @return the build rule
   */
  public static DCompileBuildRule requireBuildRule(
      BuildTarget compileTarget,
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      SourcePathRuleFinder ruleFinder,
      DBuckConfig dBuckConfig,
      ImmutableList<String> compilerFlags,
      String name,
      SourcePath src,
      DIncludes includes) {
    return (DCompileBuildRule)
        graphBuilder.computeIfAbsent(
            compileTarget,
            ignored -> {
              Tool compiler = dBuckConfig.getDCompiler();

              Map<BuildTarget, DIncludes> transitiveIncludes = new TreeMap<>();
              transitiveIncludes.put(baseBuildTarget, includes);
              for (Map.Entry<BuildTarget, DLibrary> library :
                  getTransitiveDLibraryRules(baseParams.getBuildDeps()).entrySet()) {
                transitiveIncludes.put(library.getKey(), library.getValue().getIncludes());
              }

              ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
              depsBuilder.addAll(BuildableSupport.getDepsCollection(compiler, ruleFinder));
              depsBuilder.addAll(ruleFinder.filterBuildRuleInputs(src));
              for (DIncludes dIncludes : transitiveIncludes.values()) {
                depsBuilder.addAll(dIncludes.getDeps(ruleFinder));
              }
              ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

              return new DCompileBuildRule(
                  compileTarget,
                  projectFilesystem,
                  baseParams.withDeclaredDeps(deps).withoutExtraDeps(),
                  compiler,
                  ImmutableList.<String>builder()
                      .addAll(dBuckConfig.getBaseCompilerFlags())
                      .addAll(compilerFlags)
                      .build(),
                  name,
                  ImmutableSortedSet.of(src),
                  ImmutableList.copyOf(transitiveIncludes.values()));
            });
  }

  /**
   * Generates BuildTargetPaths and BuildRules to compile D sources to object files, and returns a
   * list of SourcePaths referring to the generated object files.
   *
   * @param sources source files to compile
   * @param compilerFlags flags to pass to the compiler
   * @param baseParams build parameters for the compilation
   * @param graphBuilder graphBuilder for build rules
   * @param sourcePathResolver resolver for source paths
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @return SourcePaths of the generated object files
   */
  public static ImmutableList<SourcePath> sourcePathsForCompiledSources(
      BuildTarget baseBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams baseParams,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig,
      ImmutableList<String> compilerFlags,
      SourceSortedSet sources,
      DIncludes includes) {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    for (Map.Entry<String, SourcePath> source :
        sources.toNameMap(baseBuildTarget, sourcePathResolver, "srcs").entrySet()) {
      BuildTarget compileTarget =
          createDCompileBuildTarget(baseBuildTarget, source.getKey(), cxxPlatform);
      BuildRule rule =
          requireBuildRule(
              compileTarget,
              baseBuildTarget,
              projectFilesystem,
              baseParams,
              graphBuilder,
              ruleFinder,
              dBuckConfig,
              compilerFlags,
              source.getKey(),
              source.getValue(),
              includes);
      sourcePaths.add(rule.getSourcePathToOutput());
    }
    return sourcePaths.build();
  }
}
