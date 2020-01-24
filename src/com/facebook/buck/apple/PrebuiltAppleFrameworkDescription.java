/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.metadata.MetadataProvidingDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.stream.RichStream;
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

  private FlavorDomain<UnresolvedAppleCxxPlatform> getAppleCxxPlatformsFlavorDomain(
      TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    // This class supports flavors that other apple targets support.
    // It's mainly there to be compatible with other apple rules which blindly add flavor tags to
    // all its targets.
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformsFlavorDomain =
        getAppleCxxPlatformsFlavorDomain(toolchainTargetConfiguration);
    return RichStream.from(flavors)
        .allMatch(
            flavor ->
                declaredPlatforms.contains(flavor)
                    || appleCxxPlatformsFlavorDomain.getFlavors().contains(flavor)
                    || appleCxxPlatformsFlavorDomain.getFlavors().contains(flavor)
                    || AppleDebugFormat.FLAVOR_DOMAIN.getFlavors().contains(flavor)
                    || AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors().contains(flavor)
                    || StripStyle.FLAVOR_DOMAIN.getFlavors().contains(flavor));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            getAppleCxxPlatformsFlavorDomain(toolchainTargetConfiguration),
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
        context.getActionGraphBuilder(),
        args.getFramework(),
        args.getPreferredLinkage(),
        args.getFrameworks(),
        args.getSupportedPlatformsRegex(),
        input ->
            CxxFlags.getFlagsWithPlatformMacroExpansion(
                args.getExportedLinkerFlags(), args.getExportedPlatformLinkerFlags(), input),
        getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration()));
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

  @RuleArg
  interface AbstractPrebuiltAppleFrameworkDescriptionArg
      extends BuildRuleArg, HasDeclaredDeps, HasSystemFrameworkAndLibraries {
    SourcePath getFramework();

    Optional<Pattern> getSupportedPlatformsRegex();

    ImmutableList<String> getExportedLinkerFlags();

    Optional<Boolean> getCodeSignOnCopy();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<String>> getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    NativeLinkableGroup.Linkage getPreferredLinkage();
  }
}
