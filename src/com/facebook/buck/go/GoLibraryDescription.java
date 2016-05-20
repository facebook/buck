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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.NoopBuildRuleWithTests;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.List;

public class GoLibraryDescription implements
    Description<GoLibraryDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<GoLibraryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_library");

  private final GoBuckConfig goBuckConfig;

  public GoLibraryDescription(GoBuckConfig goBuckConfig) {
    this.goBuckConfig = goBuckConfig;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return goBuckConfig.getPlatformFlavorDomain().containsAnyOf(flavors);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      final BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    Optional<GoPlatform> platform =
        goBuckConfig.getPlatformFlavorDomain().getValue(buildTarget);

    if (metadataClass.isAssignableFrom(GoLinkable.class)) {
      Preconditions.checkState(platform.isPresent());
      SourcePath output = new BuildTargetSourcePath(
          resolver.requireRule(buildTarget).getBuildTarget());
      return Optional.of(
          metadataClass.cast(
              GoLinkable.builder()
                  .setGoLinkInput(
                      ImmutableMap.of(
                          args.packageName.transform(MorePaths.TO_PATH)
                              .or(goBuckConfig.getDefaultPackageName(buildTarget)),
                          output))
                  .setExportedDeps(args.exportedDeps.or(ImmutableSortedSet.<BuildTarget>of()))
                  .build()));
    } else if (buildTarget.getFlavors().contains(GoDescriptors.TRANSITIVE_LINKABLES_FLAVOR)) {
      Preconditions.checkState(platform.isPresent());

      return Optional.of(metadataClass.cast(GoDescriptors.requireTransitiveGoLinkables(
          buildTarget,
          resolver,
          platform.get(),
          args.deps.get(),
          /* includeSelf */ true)));
    } else {
      return Optional.absent();
    }
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<GoPlatform> platform =
        goBuckConfig.getPlatformFlavorDomain().getValue(params.getBuildTarget());

    if (platform.isPresent()) {
      return GoDescriptors.createGoCompileRule(
          params,
          resolver,
          goBuckConfig,
          args.packageName.transform(MorePaths.TO_PATH)
              .or(goBuckConfig.getDefaultPackageName(params.getBuildTarget())),
          args.srcs,
          args.compilerFlags.or(ImmutableList.<String>of()),
          args.assemblerFlags.get(),
          platform.get());
    }

    return new NoopBuildRuleWithTests(params, new SourcePathResolver(resolver), args.tests.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public ImmutableSet<SourcePath> srcs;
    public Optional<List<String>> compilerFlags;
    public Optional<List<String>> assemblerFlags;
    public Optional<String> packageName;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;

    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }
  }
}
