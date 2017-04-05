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
package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltAppleFrameworkDescription implements
    Description<PrebuiltAppleFrameworkDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<PrebuiltAppleFrameworkDescription.Arg> {

  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain;

  public PrebuiltAppleFrameworkDescription(
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain) {
    this.appleCxxPlatformsFlavorDomain = appleCxxPlatformsFlavorDomain;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    // This class supports flavors that other apple targets support.
    // It's mainly there to be compatible with other apple rules which blindly add flavor tags to
    // all its targets.
    return RichStream.from(flavors)
        .filter(flavor -> !appleCxxPlatformsFlavorDomain.getFlavors().contains(flavor))
        .filter(flavor -> !AppleDebugFormat.FLAVOR_DOMAIN.getFlavors().contains(flavor))
        .filter(flavor -> !AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors().contains(flavor))
        .filter(flavor -> !StripStyle.FLAVOR_DOMAIN.getFlavors().contains(flavor))
        .count() == 0;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(
        appleCxxPlatformsFlavorDomain,
        AppleDebugFormat.FLAVOR_DOMAIN,
        AppleDescriptions.INCLUDE_FRAMEWORKS,
        StripStyle.FLAVOR_DOMAIN));
  }

  @Override
  public PrebuiltAppleFrameworkDescription.Arg createUnpopulatedConstructorArg() {
    return new PrebuiltAppleFrameworkDescription.Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final A args) throws NoSuchBuildTargetException {
    return new PrebuiltAppleFramework(
        params,
        resolver,
        new SourcePathResolver(new SourcePathRuleFinder(resolver)),
        args.framework,
        args.preferredLinkage,
        args.frameworks,
        args.supportedPlatformsRegex,
        input -> CxxFlags.getFlagsWithPlatformMacroExpansion(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            input)
    );
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      BuildRule buildRule = resolver.requireRule(buildTarget);
      ImmutableSet<SourcePath> sourcePaths = ImmutableSet.of(buildRule.getSourcePathToOutput());
      return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths)));
    }
    return Optional.empty();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg
      implements HasSystemFrameworkAndLibraries {
    public SourcePath framework;
    public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
    public Optional<Pattern> supportedPlatformsRegex;
    public ImmutableList<String> exportedLinkerFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<String>> exportedPlatformLinkerFlags =
        PatternMatchedCollection.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public NativeLinkable.Linkage preferredLinkage;

    @Override
    public ImmutableSortedSet<FrameworkPath> getFrameworks() {
      return frameworks;
    }

    @Override
    public ImmutableSortedSet<FrameworkPath> getLibraries() {
      return ImmutableSortedSet.of();
    }
  }
}
