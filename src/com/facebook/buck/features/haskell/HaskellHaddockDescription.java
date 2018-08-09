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
package com.facebook.buck.features.haskell;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class HaskellHaddockDescription
    implements DescriptionWithTargetGraph<HaskellHaddockDescriptionArg>,
        ImplicitDepsInferringDescription<
            HaskellHaddockDescription.AbstractHaskellHaddockDescriptionArg>,
        VersionPropagator<HaskellHaddockDescriptionArg> {

  private static final Logger LOG = Logger.get(HaskellHaddockDescription.class);

  private final ToolchainProvider toolchainProvider;

  public HaskellHaddockDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<HaskellHaddockDescriptionArg> getConstructorArgType() {
    return HaskellHaddockDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget baseTarget,
      BuildRuleParams params,
      HaskellHaddockDescriptionArg args) {
    String name = baseTarget.getShortName();
    LOG.info("Creating Haddock " + name);

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    HaskellPlatform platform = getPlatform(baseTarget, args);
    ImmutableCollection<BuildRule> deps = graphBuilder.getAllRules(args.getDeps());

    // Collect all Haskell deps
    ImmutableSet.Builder<HaskellHaddockInput> haddockInputs = ImmutableSet.builder();

    // Traverse all deps to pull packages + locations
    new AbstractBreadthFirstTraversal<BuildRule>(deps) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        ImmutableSet.Builder<BuildRule> traverse = ImmutableSet.builder();
        if (rule instanceof HaskellCompileDep) {
          HaskellCompileDep haskellCompileDep = (HaskellCompileDep) rule;

          // Only index first order dependencies
          if (deps.contains(rule)) {
            haddockInputs.add(haskellCompileDep.getHaddockInput(platform));
          }

          traverse.addAll(haskellCompileDep.getCompileDeps(platform));
        }
        return traverse.build();
      }
    }.start();

    return graphBuilder.addToIndex(
        HaskellHaddockRule.from(
            baseTarget,
            context.getProjectFilesystem(),
            params,
            ruleFinder,
            platform.getHaddock().resolve(graphBuilder),
            args.getHaddockFlags(),
            haddockInputs.build()));
  }

  // Return the C/C++ platform to build against.
  private HaskellPlatform getPlatform(
      BuildTarget target, AbstractHaskellHaddockDescriptionArg arg) {
    HaskellPlatformsProvider haskellPlatformsProvider =
        toolchainProvider.getByName(
            HaskellPlatformsProvider.DEFAULT_NAME, HaskellPlatformsProvider.class);
    FlavorDomain<HaskellPlatform> platforms = haskellPlatformsProvider.getHaskellPlatforms();

    Optional<HaskellPlatform> flavorPlatform = platforms.getValue(target);
    if (flavorPlatform.isPresent()) {
      return flavorPlatform.get();
    }

    if (arg.getPlatform().isPresent()) {
      return platforms.getValue(arg.getPlatform().get());
    }

    return haskellPlatformsProvider.getDefaultHaskellPlatform();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractHaskellHaddockDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    HaskellDescriptionUtils.getParseTimeDeps(
        ImmutableList.of(getPlatform(buildTarget, constructorArg)), targetGraphOnlyDepsBuilder);

    constructorArg
        .getDepsQuery()
        .ifPresent(
            depsQuery ->
                QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, depsQuery)
                    .forEach(targetGraphOnlyDepsBuilder::add));
  }

  @BuckStyleImmutable
  @Value.Immutable(copy = true)
  interface AbstractHaskellHaddockDescriptionArg extends CommonDescriptionArg, HasDepsQuery {
    Optional<Flavor> getPlatform();

    @Value.Default
    default ImmutableList<String> getHaddockFlags() {
      return ImmutableList.of();
    }
  }
}
