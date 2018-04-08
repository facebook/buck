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

import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class GoBinaryDescription
    implements Description<GoBinaryDescriptionArg>,
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
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GoBinaryDescriptionArg args) {
    GoPlatform platform = getGoPlatform(buildTarget, args);
    return GoDescriptors.createGoBinaryRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getBuildRuleResolver(),
        goBuckConfig,
        args.getSrcs(),
        args.getCompilerFlags(),
        args.getAssemblerFlags(),
        args.getLinkerFlags(),
        platform,
        args.getCgoDeps());
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
      extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs, HasCgo {
    Optional<Flavor> getPlatform();

    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    ImmutableList<String> getLinkerFlags();
  }
}
