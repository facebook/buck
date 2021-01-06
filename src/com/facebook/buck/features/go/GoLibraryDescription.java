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

package com.facebook.buck.features.go;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.impl.CxxPlatforms;
import com.facebook.buck.features.go.GoListStep.ListType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.immutables.value.Value;

public class GoLibraryDescription
    implements DescriptionWithTargetGraph<GoLibraryDescriptionArg>,
        Flavored,
        MetadataProvidingDescription<GoLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<GoLibraryDescriptionArg>,
        VersionPropagator<GoLibraryDescriptionArg> {

  private final GoBuckConfig goBuckConfig;
  private final ToolchainProvider toolchainProvider;

  public GoLibraryDescription(GoBuckConfig goBuckConfig, ToolchainProvider toolchainProvider) {
    this.goBuckConfig = goBuckConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<GoLibraryDescriptionArg> getConstructorArgType() {
    return GoLibraryDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return getGoToolchain(toolchainTargetConfiguration)
        .getPlatformFlavorDomain()
        .containsAnyOf(flavors);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      GoLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain(buildTarget.getTargetConfiguration())
            .getPlatformFlavorDomain()
            .getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)) {
      Preconditions.checkState(platform.isPresent());
      SourcePath output = graphBuilder.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.of(
                  ImmutableMap.of(
                      args.getPackageName()
                          .map(Paths::get)
                          .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
                      output),
                  args.getExportedDeps())));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      return Optional.of(
          metadataClass.cast(
              GoDescriptors.requireTransitiveGoLinkables(
                  buildTarget,
                  graphBuilder,
                  platform.get(),
                  Iterables.concat(args.getDeps(), args.getExportedDeps()),
                  /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoLibraryDescriptionArg args) {
    GoToolchain goToolchain = getGoToolchain(buildTarget.getTargetConfiguration());
    Optional<GoPlatform> platform = goToolchain.getPlatformFlavorDomain().getValue(buildTarget);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (platform.isPresent()) {
      return GoDescriptors.createGoCompileRule(
          buildTarget,
          projectFilesystem,
          params,
          context.getActionGraphBuilder(),
          goBuckConfig,
          args.getPackageName()
              .map(Paths::get)
              .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
          args.getSrcs(),
          args.getCompilerFlags(),
          args.getAssemblerFlags(),
          platform.get(),
          new ImmutableList.Builder<BuildTarget>()
              .addAll(
                  params.getDeclaredDeps().get().stream().map(BuildRule::getBuildTarget).iterator())
              .addAll(args.getExportedDeps())
              .build(),
          ImmutableList.of(),
          Collections.singletonList(ListType.GoFiles));
    }

    return new GoLibrary(buildTarget, projectFilesystem, params, args.getSrcs());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      GoLibraryDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    GoToolchain toolchain = getGoToolchain(buildTarget.getTargetConfiguration());
    toolchain
        .getPlatformFlavorDomain()
        .getValue(buildTarget)
        .ifPresent(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    CxxPlatforms.getParseTimeDeps(
                        buildTarget.getTargetConfiguration(), platform.getCxxPlatform())));
  }

  private GoToolchain getGoToolchain(TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        GoToolchain.DEFAULT_NAME, toolchainTargetConfiguration, GoToolchain.class);
  }

  @RuleArg
  interface AbstractGoLibraryDescriptionArg
      extends BuildRuleArg, HasDeclaredDeps, HasSrcs, HasTests {
    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    Optional<String> getPackageName();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();
  }
}
