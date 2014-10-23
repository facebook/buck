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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class AppleLibraryDescription implements
    Description<AppleNativeTargetDescriptionArg>, Flavored {
  public static final BuildRuleType TYPE = new BuildRuleType("apple_library");

  public static final Flavor DYNAMIC_LIBRARY = new Flavor("dynamic");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CompilationDatabase.COMPILATION_DATABASE,
      Flavor.DEFAULT,
      DYNAMIC_LIBRARY,
      AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final AppleConfig appleConfig;

  public AppleLibraryDescription(AppleConfig appleConfig) {
    this.appleConfig = appleConfig;
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
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR);
  }

  @Override
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    TargetSources targetSources = TargetSources.ofAppleSources(pathResolver, args.srcs.get());
    Optional<BuildRule> flavoredRule = AbstractAppleNativeTargetBuildRuleDescriptions
        .createFlavoredRule(
            params,
            resolver,
            args,
            appleConfig,
            pathResolver,
            targetSources);
    if (flavoredRule.isPresent()) {
      return flavoredRule.get();
    }

    // When creating an unflavored AppleLibrary, ensure that the #headers flavor is created, as
    // well.
    BuildRule headersRule = AbstractAppleNativeTargetBuildRuleDescriptions
        .createHeadersFlavorIfNotAlreadyPresent(params, resolver, args);
    if (params.getBuildTarget().getFlavors().contains(
        AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS)) {
      return headersRule;
    } else if (!resolver.getRuleOptional(headersRule.getBuildTarget()).isPresent()) {
      resolver.addToIndex(headersRule);
    }

    return new AppleLibrary(
        params,
        pathResolver,
        args,
        targetSources,
        params.getBuildTarget().getFlavors().contains(DYNAMIC_LIBRARY));
  }
}
