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

package com.facebook.buck.go;

import com.facebook.buck.go.GoListStep.FileType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.immutables.value.Value;

public class GoLibraryDescription
    implements Description<GoLibraryDescriptionArg>,
        Flavored,
        MetadataProvidingDescription<GoLibraryDescriptionArg>,
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
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return getGoToolchain().getPlatformFlavorDomain().containsAnyOf(flavors);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GoLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain().getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)) {
      Preconditions.checkState(platform.isPresent());
      SourcePath output = resolver.requireRule(buildTarget).getSourcePathToOutput();
      return Optional.of(
          metadataClass.cast(
              GoLinkable.builder()
                  .setGoLinkInput(
                      ImmutableMap.of(
                          args.getPackageName()
                              .map(Paths::get)
                              .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
                          output))
                  .setExportedDeps(args.getExportedDeps())
                  .build()));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      return Optional.of(
          metadataClass.cast(
              GoDescriptors.requireTransitiveGoLinkables(
                  buildTarget,
                  resolver,
                  platform.get(),
                  Iterables.concat(args.getDeps(), args.getExportedDeps()),
                  /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoLibraryDescriptionArg args) {
    GoToolchain goToolchain = getGoToolchain();
    Optional<GoPlatform> platform = goToolchain.getPlatformFlavorDomain().getValue(buildTarget);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    if (platform.isPresent()) {
      return GoDescriptors.createGoCompileRule(
          buildTarget,
          projectFilesystem,
          params,
          context.getBuildRuleResolver(),
          goBuckConfig,
          goToolchain,
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
          args.getCgoDeps(),
          Arrays.asList(FileType.GoFiles));
    }

    return new NoopBuildRuleWithDeclaredAndExtraDeps(buildTarget, projectFilesystem, params);
  }

  private GoToolchain getGoToolchain() {
    return toolchainProvider.getByName(GoToolchain.DEFAULT_NAME, GoToolchain.class);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasTests, HasCgo {
    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    Optional<String> getPackageName();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();
  }
}
