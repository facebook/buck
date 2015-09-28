/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class AppleBinaryDescription
    implements Description<AppleNativeTargetDescriptionArg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_binary");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final CxxBinaryDescription delegate;

  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;

  public AppleBinaryDescription(
      CxxBinaryDescription delegate,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms) {
    this.delegate = delegate;
    this.platformFlavorsToAppleCxxPlatforms =
        ImmutableMap.copyOf(platformFlavorsToAppleCxxPlatforms);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleNativeTargetDescriptionArg createUnpopulatedConstructorArg() {
    return new AppleNativeTargetDescriptionArg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR)) {
      return true;
    }
    Collection<ImmutableSortedSet<Flavor>> thinFlavorSets =
        FatBinaryInfo.generateThinFlavors(
            platformFlavorsToAppleCxxPlatforms.keySet(),
            ImmutableSortedSet.copyOf(flavors));
    if (thinFlavorSets.size() > 1) {
      return Iterables.all(
          thinFlavorSets,
          new Predicate<ImmutableSortedSet<Flavor>>() {
            @Override
            public boolean apply(ImmutableSortedSet<Flavor> input) {
              return delegate.hasFlavors(input);
            }
          });
    } else {
      return delegate.hasFlavors(flavors);
    }
  }

  @Override
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    Optional<FatBinaryInfo> fatBinaryInfo = FatBinaryInfo.create(
        platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget());

    if (fatBinaryInfo.isPresent()) {
      return createFatBinaryBuildRule(targetGraph, params, resolver, args, fatBinaryInfo.get());
    } else {
      CxxBinaryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxBinaryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          params.getBuildTarget());

      return delegate.createBuildRule(targetGraph, params, resolver, delegateArg);
    }
  }

  /**
   * Create a fat binary rule.
   */
  private <A extends AppleNativeTargetDescriptionArg> BuildRule createFatBinaryBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      FatBinaryInfo fatBinaryInfo) {
    ImmutableSortedSet.Builder<BuildRule> thinRules = ImmutableSortedSet.naturalOrder();
    for (BuildTarget thinTarget : fatBinaryInfo.getThinTargets()) {
      if (resolver.getRuleOptional(thinTarget).isPresent()) {
        continue;
      }
      BuildRule thinRule = createBuildRule(
          targetGraph,
          params.copyWithBuildTarget(thinTarget),
          resolver,
          args);
      resolver.addToIndex(thinRule);
      thinRules.add(thinRule);
    }
    ImmutableSortedSet<SourcePath> inputs = FluentIterable
        .from(resolver.getAllRules(fatBinaryInfo.getThinTargets()))
        .transform(SourcePaths.getToBuildTargetSourcePath())
        .toSortedSet(Ordering.natural());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new FatBinary(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(thinRules.build())),
        pathResolver,
        fatBinaryInfo.getRepresentativePlatform().getLipo(),
        inputs,
        BuildTargets.getGenPath(params.getBuildTarget(), "%s"));
  }
}
