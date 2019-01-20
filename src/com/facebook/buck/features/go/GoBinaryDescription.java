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

package com.facebook.buck.features.go;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

public class GoBinaryDescription
    implements DescriptionWithTargetGraph<GoBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<GoBinaryDescription.AbstractGoBinaryDescriptionArg>,
        VersionRoot<GoBinaryDescriptionArg>,
        Flavored {

  private final GoBuckConfig goBuckConfig;
  private final ToolchainProvider toolchainProvider;

  public GoBinaryDescription(GoBuckConfig goBuckConfig, ToolchainProvider toolchainProvider) {
    this.goBuckConfig = goBuckConfig;
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<GoBinaryDescriptionArg> getConstructorArgType() {
    return GoBinaryDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return getGoToolchain().getPlatformFlavorDomain().containsAnyOf(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoBinaryDescriptionArg args) {
    GoPlatform platform = getGoPlatform(buildTarget, args);
    return GoDescriptors.createGoBinaryRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getActionGraphBuilder(),
        goBuckConfig,
        args.getLinkStyle().orElse(Linker.LinkableDepType.STATIC_PIC),
        args.getLinkMode(),
        args.getSrcs(),
        args.getResources(),
        args.getCompilerFlags(),
        args.getAssemblerFlags(),
        args.getLinkerFlags(),
        args.getExternalLinkerFlags(),
        platform);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractGoBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add the C/C++ linker parse time deps.
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getGoPlatform(buildTarget, constructorArg).getCxxPlatform()));
  }

  private GoToolchain getGoToolchain() {
    return toolchainProvider.getByName(GoToolchain.DEFAULT_NAME, GoToolchain.class);
  }

  private GoPlatform getGoPlatform(BuildTarget target, AbstractGoBinaryDescriptionArg arg) {
    GoToolchain toolchain = getGoToolchain();
    FlavorDomain<GoPlatform> platforms = toolchain.getPlatformFlavorDomain();
    return platforms
        .getValue(target)
        .orElseGet(
            () ->
                arg.getPlatform()
                    .map(platforms::getValue)
                    .orElseGet(toolchain::getDefaultPlatform));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoBinaryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasGoLinkable {}
}
