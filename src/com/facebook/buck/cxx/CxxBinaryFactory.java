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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class CxxBinaryFactory {

  private final ToolchainProvider toolchainProvider;
  private final CxxBuckConfig cxxBuckConfig;
  private final InferBuckConfig inferBuckConfig;

  public CxxBinaryFactory(
      ToolchainProvider toolchainProvider,
      CxxBuckConfig cxxBuckConfig,
      InferBuckConfig inferBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.cxxBuckConfig = cxxBuckConfig;
    this.inferBuckConfig = inferBuckConfig;
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public BuildRule createBuildRule(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxBinaryDescriptionArg args,
      ImmutableSortedSet<BuildTarget> extraCxxDeps) {

    // We explicitly remove some flavors below from params to make sure rule
    // has the same output regardless if we will strip or not.
    Optional<StripStyle> flavoredStripStyle = StripStyle.FLAVOR_DOMAIN.getValue(target);
    Optional<LinkerMapMode> flavoredLinkerMapMode = LinkerMapMode.FLAVOR_DOMAIN.getValue(target);
    target = CxxStrip.removeStripStyleFlavorInTarget(target, flavoredStripStyle);
    target = LinkerMapMode.removeLinkerMapModeFlavorInTarget(target, flavoredLinkerMapMode);

    CxxPlatformsProvider cxxPlatformsProvider = getCxxPlatformsProvider();

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(target.getFlavors());
    CxxPlatform cxxPlatform =
        CxxPlatforms.getCxxPlatform(cxxPlatformsProvider, target, args.getDefaultPlatform())
            .resolve(graphBuilder);
    if (flavors.contains(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR)) {
      return createHeaderSymlinkTreeBuildRule(
          target.withoutFlavors(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
          projectFilesystem,
          graphBuilder,
          cxxPlatform,
          args);
    }

    if (flavors.contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      CxxLinkAndCompileRules cxxLinkAndCompileRules =
          CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
              target.withoutFlavors(CxxCompilationDatabase.COMPILATION_DATABASE),
              projectFilesystem,
              graphBuilder,
              cellRoots,
              cxxBuckConfig,
              cxxPlatform,
              args,
              ImmutableSet.of(),
              flavoredStripStyle,
              flavoredLinkerMapMode);
      return CxxCompilationDatabase.createCompilationDatabase(
          target, projectFilesystem, cxxLinkAndCompileRules.compileRules);
    }

    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();

    if (flavors.contains(CxxCompilationDatabase.UBER_COMPILATION_DATABASE)) {
      return CxxDescriptionEnhancer.createUberCompilationDatabase(
          cxxPlatforms.getValue(flavors).isPresent()
              ? target
              : target.withAppendedFlavors(
                  cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform().getFlavor()),
          projectFilesystem,
          graphBuilder);
    }

    if (CxxInferEnhancer.INFER_FLAVOR_DOMAIN.containsAnyOf(flavors)) {
      return CxxInferEnhancer.requireInferRule(
          target,
          projectFilesystem,
          graphBuilder,
          cellRoots,
          cxxBuckConfig,
          cxxPlatform,
          args,
          inferBuckConfig);
    }

    if (flavors.contains(CxxDescriptionEnhancer.INCREMENTAL_THINLTO)) {
      return CxxDescriptionEnhancer.createBuildRuleForCxxThinLtoBinary(
          target,
          projectFilesystem);
    }

    CxxLinkAndCompileRules cxxLinkAndCompileRules =
        CxxDescriptionEnhancer.createBuildRulesForCxxBinaryDescriptionArg(
            target,
            projectFilesystem,
            graphBuilder,
            cellRoots,
            cxxBuckConfig,
            cxxPlatform,
            args,
            extraCxxDeps,
            flavoredStripStyle,
            flavoredLinkerMapMode);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    if (target.getFlavors().contains(CxxDescriptionEnhancer.CXX_LINK_MAP_FLAVOR)) {
      return CxxDescriptionEnhancer.createLinkMap(
          target, projectFilesystem, ruleFinder, cxxLinkAndCompileRules);
    }

    // Return a CxxBinary rule as our representative in the action graph, rather than the CxxLink
    // rule above for a couple reasons:
    //  1) CxxBinary extends BinaryBuildRule whereas CxxLink does not, so the former can be used
    //     as executables for genrules.
    //  2) In some cases, users add dependencies from some rules onto other binary rules, typically
    //     if the binary is executed by some test or library code at test time.  These target graph
    //     deps should *not* become build time dependencies on the CxxLink step, otherwise we'd
    //     have to wait for the dependency binary to link before we could link the dependent binary.
    //     By using another BuildRule, we can keep the original target graph dependency tree while
    //     preventing it from affecting link parallelism.

    target = CxxStrip.restoreStripStyleFlavorInTarget(target, flavoredStripStyle);
    target = LinkerMapMode.restoreLinkerMapModeFlavorInTarget(target, flavoredLinkerMapMode);
    return new CxxBinary(
        target,
        projectFilesystem,
        new BuildRuleParams(
            () -> cxxLinkAndCompileRules.deps,
            () ->
                ImmutableSortedSet.copyOf(
                    BuildableSupport.getDepsCollection(
                        cxxLinkAndCompileRules.executable, ruleFinder)),
            ImmutableSortedSet.of()),
        cxxPlatform,
        cxxLinkAndCompileRules.getBinaryRule(),
        cxxLinkAndCompileRules.executable,
        args.getFrameworks(),
        args.getTests(),
        target.withoutFlavors(cxxPlatforms.getFlavors()),
        cxxBuckConfig.shouldCacheBinaries());
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  /** @return a {@link HeaderSymlinkTree} for the headers of this C/C++ binary. */
  private static HeaderSymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxBinaryDescriptionArg args) {
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
        true);
  }
}
