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

package com.facebook.buck.d;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.MoreMaps;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Map;
import java.util.TreeMap;

/**
 * Utility functions for use in D Descriptions.
 */
abstract class DDescriptionUtils {

  public static final Flavor SOURCE_LINK_TREE = ImmutableFlavor.of("source-link-tree");

  /**
   * Creates a BuildTarget, based on an existing build target, but flavored with a CxxPlatform
   * and an additional flavor created by combining a prefix and an output file name.
   *
   * @param existingTarget the existing target
   * @param flavorPrefix prefix to be used for added flavor
   * @param fileName filename to be used for added flavor
   * @param cxxPlatform the C++ platform to compile for
   * @return the new BuildTarget
   */
  public static BuildTarget createBuildTargetForFile(
      BuildTarget existingTarget,
      String flavorPrefix,
      String fileName,
      CxxPlatform cxxPlatform) {
    return BuildTarget.builder(existingTarget)
        .addFlavors(
            cxxPlatform.getFlavor(),
            ImmutableFlavor.of(
                flavorPrefix + Flavor.replaceInvalidCharacters(fileName)))
        .build();
  }

  /**
   * Creates a new BuildTarget, based on an existing target, for a file to be compiled.
   * @param existingTarget the existing target
   * @param src the source file to be compiled
   * @param cxxPlatform the C++ platform to compile the file for
   * @return a BuildTarget to compile a D source file to an object file
   */
  public static BuildTarget createDCompileBuildTarget(
      BuildTarget existingTarget,
      String src,
      CxxPlatform cxxPlatform) {
    return createBuildTargetForFile(
        existingTarget,
        "compile-",
        DCompileStep.getObjectNameForSourceName(src),
        cxxPlatform);
  }

  /**
   * Creates a {@link com.facebook.buck.cxx.NativeLinkable} using sources compiled by
   * the D compiler.
   *
   * @param params build parameters for the build target
   * @param sources source files to compile
   * @param compilerFlags flags to pass to the compiler
   * @param buildRuleResolver resolver for build rules
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @return the new build rule
   */
  public static CxxLink createNativeLinkable(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      ImmutableList<String> compilerFlags,
      SourceList sources,
      DIncludes includes)
      throws NoSuchBuildTargetException {

    BuildTarget buildTarget = params.getBuildTarget();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);

    ImmutableList<SourcePath> sourcePaths =
        sourcePathsForCompiledSources(
            params,
            buildRuleResolver,
            sourcePathResolver,
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
        params,
        buildRuleResolver,
        sourcePathResolver,
        buildTarget,
        Linker.LinkType.EXECUTABLE,
        Optional.<String>absent(),
        BuildTargets.getGenPath(buildTarget, "%s/" + buildTarget.getShortName()),
        Linker.LinkableDepType.STATIC,
        FluentIterable.from(params.getDeps())
            .filter(NativeLinkable.class),
        /* cxxRuntimeType */ Optional.<Linker.CxxRuntimeType>absent(),
        /* bundleLoader */ Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of(),
        NativeLinkableInput.builder()
            .addAllArgs(StringArg.from(dBuckConfig.getLinkerFlags()))
            .addAllArgs(SourcePathArg.from(sourcePathResolver, sourcePaths))
            .build());
  }

  public static BuildTarget getSymlinkTreeTarget(BuildTarget baseTarget) {
    return BuildTarget.builder(baseTarget)
        .addFlavors(SOURCE_LINK_TREE)
        .build();
  }

  public static SymlinkTree createSourceSymlinkTree(
      BuildTarget target,
      BuildRuleParams baseParams,
      SourcePathResolver pathResolver,
      SourceList sources) {
    Preconditions.checkState(target.getFlavors().contains(SOURCE_LINK_TREE));
    return new SymlinkTree(
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        baseParams.getProjectFilesystem().resolve(
            BuildTargets.getGenPath(baseParams.getBuildTarget(), "%s")),
        MoreMaps.transformKeys(
            sources.toNameMap(
                baseParams.getBuildTarget(),
                pathResolver,
                "srcs"),
            MorePaths.toPathFn(baseParams.getProjectFilesystem().getRootPath().getFileSystem())));
  }

  private static ImmutableMap<BuildTarget, DLibrary> getTransitiveDLibraryRules(
      Iterable<? extends BuildRule> inputs) {
    final ImmutableMap.Builder<BuildTarget, DLibrary> libraries = ImmutableMap.builder();
    new AbstractBreadthFirstTraversal<BuildRule>(inputs) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (rule instanceof DLibrary) {
          libraries.put(rule.getBuildTarget(), (DLibrary) rule);
          return rule.getDeps();
        }
        return ImmutableSet.of();
      }
    }.start();
    return libraries.build();
  }

  /**
   * Ensures that a DCompileBuildRule exists for the given target, creating a DCompileBuildRule
   * if neccesary.
   * @param baseParams build parameters for the rule
   * @param buildRuleResolver BuildRuleResolver the rule should be in
   * @param sourcePathResolver used to resolve source paths
   * @param src the source file to be compiled
   * @param compilerFlags flags to pass to the compiler
   * @param compileTarget the target the rule should be for
   * @param dBuckConfig the Buck configuration for D
   * @return the build rule
   */
  public static DCompileBuildRule requireBuildRule(
      BuildTarget compileTarget,
      BuildRuleParams baseParams,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      DBuckConfig dBuckConfig,
      ImmutableList<String> compilerFlags,
      String name,
      SourcePath src,
      DIncludes includes)
      throws NoSuchBuildTargetException {
    Optional<BuildRule> existingRule = buildRuleResolver.getRuleOptional(compileTarget);
    if (existingRule.isPresent()) {
      return (DCompileBuildRule) existingRule.get();
    } else {
      Tool compiler = dBuckConfig.getDCompiler();

      Map<BuildTarget, DIncludes> transitiveIncludes = new TreeMap<>();
      transitiveIncludes.put(baseParams.getBuildTarget(), includes);
      for (Map.Entry<BuildTarget, DLibrary> library :
           getTransitiveDLibraryRules(baseParams.getDeps()).entrySet()) {
        transitiveIncludes.put(library.getKey(), library.getValue().getIncludes());
      }

      ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();
      depsBuilder.addAll(compiler.getDeps(sourcePathResolver));
      depsBuilder.addAll(sourcePathResolver.filterBuildRuleInputs(src));
      for (DIncludes dIncludes : transitiveIncludes.values()) {
        depsBuilder.addAll(dIncludes.getDeps(sourcePathResolver));
      }
      ImmutableSortedSet<BuildRule> deps = depsBuilder.build();

      return buildRuleResolver.addToIndex(
          new DCompileBuildRule(
              baseParams.copyWithChanges(
                  compileTarget,
                  Suppliers.ofInstance(deps),
                  Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
              sourcePathResolver,
              compiler,
              ImmutableList.<String>builder()
                  .addAll(dBuckConfig.getBaseCompilerFlags())
                  .addAll(compilerFlags)
                  .build(),
              name,
              ImmutableSortedSet.of(src),
              ImmutableList.copyOf(transitiveIncludes.values())));
    }
  }

  /**
   * Generates BuildTargets and BuildRules to compile D sources to object files, and
   * returns a list of SourcePaths referring to the generated object files.
   * @param sources source files to compile
   * @param compilerFlags flags to pass to the compiler
   * @param baseParams build parameters for the compilation
   * @param buildRuleResolver resolver for build rules
   * @param sourcePathResolver resolver for source paths
   * @param cxxPlatform the C++ platform to compile for
   * @param dBuckConfig the Buck configuration for D
   * @return SourcePaths of the generated object files
   */
  public static ImmutableList<SourcePath> sourcePathsForCompiledSources(
      BuildRuleParams baseParams,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      CxxPlatform cxxPlatform,
      DBuckConfig dBuckConfig,
      ImmutableList<String> compilerFlags,
      SourceList sources,
      DIncludes includes)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    for (Map.Entry<String, SourcePath> source :
         sources.toNameMap(baseParams.getBuildTarget(), sourcePathResolver, "srcs").entrySet()) {
      BuildTarget compileTarget =
          createDCompileBuildTarget(
              baseParams.getBuildTarget(),
              source.getKey(),
              cxxPlatform);
      requireBuildRule(
          compileTarget,
          baseParams,
          buildRuleResolver,
          sourcePathResolver,
          dBuckConfig,
          compilerFlags,
          source.getKey(),
          source.getValue(),
          includes);
      sourcePaths.add(new BuildTargetSourcePath(compileTarget));
    }
    return sourcePaths.build();
  }

}
