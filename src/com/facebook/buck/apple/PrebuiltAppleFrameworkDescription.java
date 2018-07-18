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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.MetadataProvidingDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public class PrebuiltAppleFrameworkDescription
    implements DescriptionWithTargetGraph<PrebuiltAppleFrameworkDescriptionArg>,
        Flavored,
        MetadataProvidingDescription<PrebuiltAppleFrameworkDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final ImmutableSet<Flavor> declaredPlatforms;

  public PrebuiltAppleFrameworkDescription(
      ToolchainProvider toolchainProvider, CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.declaredPlatforms = cxxBuckConfig.getDeclaredPlatforms();
  }

  private FlavorDomain<AppleCxxPlatform> getAppleCxxPlatformsFlavorDomain() {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getAppleCxxPlatforms();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    // This class supports flavors that other apple targets support.
    // It's mainly there to be compatible with other apple rules which blindly add flavor tags to
    // all its targets.
    FlavorDomain<AppleCxxPlatform> appleCxxPlatformsFlavorDomain =
        getAppleCxxPlatformsFlavorDomain();
    return RichStream.from(flavors)
            .filter(flavor -> !declaredPlatforms.contains(flavor))
            .filter(flavor -> !appleCxxPlatformsFlavorDomain.getFlavors().contains(flavor))
            .filter(flavor -> !appleCxxPlatformsFlavorDomain.getFlavors().contains(flavor))
            .filter(flavor -> !AppleDebugFormat.FLAVOR_DOMAIN.getFlavors().contains(flavor))
            .filter(flavor -> !AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors().contains(flavor))
            .filter(flavor -> !StripStyle.FLAVOR_DOMAIN.getFlavors().contains(flavor))
            .count()
        == 0;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(
        ImmutableSet.of(
            getAppleCxxPlatformsFlavorDomain(),
            AppleDebugFormat.FLAVOR_DOMAIN,
            AppleDescriptions.INCLUDE_FRAMEWORKS,
            StripStyle.FLAVOR_DOMAIN));
  }

  @Override
  public Class<PrebuiltAppleFrameworkDescriptionArg> getConstructorArgType() {
    return PrebuiltAppleFrameworkDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltAppleFrameworkDescriptionArg args) {
    return new PrebuiltAppleFramework(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(context.getActionGraphBuilder())),
        args.getFramework(),
        args.getPreferredLinkage(),
        args.getFrameworks(),
        args.getSupportedPlatformsRegex(),
        input ->
            CxxFlags.getFlagsWithPlatformMacroExpansion(
                args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input),
        getAppleCxxPlatformsFlavorDomain());
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      PrebuiltAppleFrameworkDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      BuildRule buildRule = graphBuilder.requireRule(buildTarget);
      ImmutableSet<SourcePath> sourcePaths = ImmutableSet.of(buildRule.getSourcePathToOutput());
      return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths)));
    }
    return Optional.empty();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltAppleFrameworkDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasSystemFrameworkAndLibraries {
    SourcePath getFramework();

    Optional<Pattern> getSupportedPlatformsRegex();

    ImmutableList<String> getExportedLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    NativeLinkable.Linkage getPreferredLinkage();
  }
}
