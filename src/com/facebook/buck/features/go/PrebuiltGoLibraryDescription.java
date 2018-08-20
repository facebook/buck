/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

public class PrebuiltGoLibraryDescription
    implements DescriptionWithTargetGraph<PrebuiltGoLibraryDescriptionArg>,
        MetadataProvidingDescription<PrebuiltGoLibraryDescriptionArg>,
        VersionPropagator<PrebuiltGoLibraryDescriptionArg> {

  private final GoBuckConfig goBuckConfig;
  private final ToolchainProvider toolchainProvider;

  public PrebuiltGoLibraryDescription(
      GoBuckConfig goBuckConfig, ToolchainProvider toolchainProvider) {
    this.goBuckConfig = goBuckConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<PrebuiltGoLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltGoLibraryDescriptionArg.class;
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PrebuiltGoLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    Optional<GoPlatform> platform =
        getGoToolchain().getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)) {
      Preconditions.checkState(platform.isPresent());
      SourcePath output = graphBuilder.requireRule(buildTarget).getSourcePathToOutput();
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
                  graphBuilder,
                  platform.get(),
                  Iterables.concat(args.getDeps(), args.getExportedDeps()),
                  /* includeSelf */ true)));
    } else {
      return Optional.empty();
    }
  }

  private GoToolchain getGoToolchain() {
    return toolchainProvider.getByName(GoToolchain.DEFAULT_NAME, GoToolchain.class);
  }

  @Override
  public PrebuiltGoLibrary createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltGoLibraryDescriptionArg args) {
    return new PrebuiltGoLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        args.getPackageName()
            .map(Paths::get)
            .orElse(goBuckConfig.getDefaultPackageName(buildTarget)),
        args.getLibrary(),
        args.getExportedDeps());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltGoLibraryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getLibrary();

    Optional<String> getPackageName();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();
  }
}
