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

package com.facebook.buck.features.d;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class DLibraryDescription
    implements DescriptionWithTargetGraph<DLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<DLibraryDescriptionArg>,
        VersionPropagator<DLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public DLibraryDescription(
      ToolchainProvider toolchainProvider, DBuckConfig dBuckConfig, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<DLibraryDescriptionArg> getConstructorArgType() {
    return DLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      DLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (buildTarget.getFlavors().contains(DDescriptionUtils.SOURCE_LINK_TREE)) {
      return DDescriptionUtils.createSourceSymlinkTree(
          buildTarget, projectFilesystem, graphBuilder, args.getSrcs());
    }

    BuildTarget sourceTreeTarget =
        buildTarget.withAppendedFlavors(DDescriptionUtils.SOURCE_LINK_TREE);
    DIncludes dIncludes =
        ImmutableDIncludes.of(
            DefaultBuildTargetSourcePath.of(sourceTreeTarget), args.getSrcs().getPaths());

    if (buildTarget.getFlavors().contains(CxxDescriptionEnhancer.STATIC_FLAVOR)) {
      graphBuilder.requireRule(sourceTreeTarget);
      return createStaticLibraryBuildRule(
          buildTarget,
          projectFilesystem,
          params,
          graphBuilder,
          /* compilerFlags */ ImmutableList.of(),
          args.getSrcs(),
          dIncludes,
          PicType.PDC);
    }

    return new DLibrary(buildTarget, projectFilesystem, params, graphBuilder, dIncludes);
  }

  /** @return a BuildRule that creates a static library. */
  private BuildRule createStaticLibraryBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> compilerFlags,
      SourceSortedSet sources,
      DIncludes dIncludes,
      PicType pic) {

    CxxPlatform cxxPlatform =
        DDescriptionUtils.getCxxPlatform(
            graphBuilder, toolchainProvider, dBuckConfig, buildTarget.getTargetConfiguration());

    ImmutableList<SourcePath> compiledSources =
        DDescriptionUtils.sourcePathsForCompiledSources(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            cxxPlatform,
            dBuckConfig,
            compilerFlags,
            sources,
            dIncludes);

    // Write a build rule to create the archive for this library.
    BuildTarget staticTarget =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            buildTarget, cxxPlatform.getFlavor(), pic);

    String staticLibraryName =
        CxxDescriptionEnhancer.getStaticLibraryName(
            buildTarget,
            Optional.empty(),
            cxxPlatform.getStaticLibraryExtension(),
            cxxBuckConfig.isUniqueLibraryNameEnabled());

    return Archive.from(
        staticTarget,
        projectFilesystem,
        graphBuilder,
        cxxPlatform,
        staticLibraryName,
        compiledSources);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      DLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(
        DDescriptionUtils.getUnresolvedCxxPlatform(
                toolchainProvider, buildTarget.getTargetConfiguration(), dBuckConfig)
            .getParseTimeDeps(buildTarget.getTargetConfiguration()));
  }

  @RuleArg
  interface AbstractDLibraryDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    SourceSortedSet getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}
