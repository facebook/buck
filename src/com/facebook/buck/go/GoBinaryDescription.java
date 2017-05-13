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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class GoBinaryDescription
    implements Description<GoBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<GoBinaryDescription.AbstractGoBinaryDescriptionArg>,
        Flavored {

  private final GoBuckConfig goBuckConfig;

  public GoBinaryDescription(GoBuckConfig goBuckConfig) {
    this.goBuckConfig = goBuckConfig;
  }

  @Override
  public Class<GoBinaryDescriptionArg> getConstructorArgType() {
    return GoBinaryDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return goBuckConfig.getPlatformFlavorDomain().containsAnyOf(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GoBinaryDescriptionArg args)
      throws NoSuchBuildTargetException {
    GoPlatform platform =
        goBuckConfig
            .getPlatformFlavorDomain()
            .getValue(params.getBuildTarget())
            .orElse(goBuckConfig.getDefaultPlatform());

    return GoDescriptors.createGoBinaryRule(
        params,
        resolver,
        goBuckConfig,
        args.getSrcs(),
        args.getCompilerFlags(),
        args.getAssemblerFlags(),
        args.getLinkerFlags(),
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
    GoPlatform goPlatform =
        goBuckConfig
            .getPlatformFlavorDomain()
            .getValue(buildTarget)
            .orElse(goBuckConfig.getDefaultPlatform());
    Optional<CxxPlatform> cxxPlatform = goPlatform.getCxxPlatform();
    if (cxxPlatform.isPresent()) {
      extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform.get()));
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGoBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    ImmutableList<String> getCompilerFlags();

    ImmutableList<String> getAssemblerFlags();

    ImmutableList<String> getLinkerFlags();
  }
}
