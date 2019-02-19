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

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
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
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class ApplePackageDescription
    implements DescriptionWithTargetGraph<ApplePackageDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            ApplePackageDescription.AbstractApplePackageDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final SandboxExecutionStrategy sandboxExecutionStrategy;
  private final AppleConfig config;

  public ApplePackageDescription(
      ToolchainProvider toolchainProvider,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      AppleConfig config) {
    this.toolchainProvider = toolchainProvider;
    this.sandboxExecutionStrategy = sandboxExecutionStrategy;
    this.config = config;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ApplePackageDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    BuildRule bundle =
        graphBuilder.getRule(propagateFlavorsToTarget(buildTarget, args.getBundle()));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    Optional<ApplePackageConfigAndPlatformInfo> applePackageConfigAndPlatformInfo =
        getApplePackageConfig(buildTarget);

    if (applePackageConfigAndPlatformInfo.isPresent()) {
      return new ExternallyBuiltApplePackage(
          buildTarget,
          projectFilesystem,
          sandboxExecutionStrategy,
          graphBuilder,
          params.withExtraDeps(
              () ->
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .add(bundle)
                      .addAll(
                          BuildableSupport.getDepsCollection(
                              applePackageConfigAndPlatformInfo.get().getExpandedArg(), ruleFinder))
                      .build()),
          applePackageConfigAndPlatformInfo.get(),
          Objects.requireNonNull(bundle.getSourcePathToOutput()),
          bundle.isCacheable(),
          Optional.empty(),
          toolchainProvider.getByNameIfPresent(
              AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
          toolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class),
          toolchainProvider.getByNameIfPresent(
              AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class));
    } else {
      return new BuiltinApplePackage(
          buildTarget, projectFilesystem, params, bundle, config.getZipCompressionLevel());
    }
  }

  @Override
  public Class<ApplePackageDescriptionArg> getConstructorArgType() {
    return ApplePackageDescriptionArg.class;
  }

  private BuildTarget propagateFlavorsToTarget(BuildTarget fromTarget, BuildTarget toTarget) {
    return toTarget.withAppendedFlavors(fromTarget.getFlavors());
  }

  /** Propagate the packages's flavors to its dependents. */
  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractApplePackageDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.add(propagateFlavorsToTarget(buildTarget, constructorArg.getBundle()));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(getAppleCxxPlatformFlavorDomain()));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractApplePackageDescriptionArg extends CommonDescriptionArg {
    @Hint(isDep = false)
    BuildTarget getBundle();
  }

  /**
   * Get the correct package configuration based on the platform flavors of this build target.
   *
   * <p>Validates that all named platforms yields the identical package config.
   *
   * @return If found, a package config for this target.
   * @throws HumanReadableException if there are multiple possible package configs.
   */
  private Optional<ApplePackageConfigAndPlatformInfo> getApplePackageConfig(BuildTarget target) {
    FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain = getAppleCxxPlatformFlavorDomain();
    Set<Flavor> platformFlavors = getPlatformFlavorsOrDefault(target, appleCxxPlatformFlavorDomain);

    // Ensure that different platforms generate the same config.
    // The value of this map is just for error reporting.
    Multimap<Optional<ApplePackageConfigAndPlatformInfo>, Flavor> packageConfigs =
        MultimapBuilder.hashKeys().arrayListValues().build();

    for (Flavor flavor : platformFlavors) {
      AppleCxxPlatform platform = appleCxxPlatformFlavorDomain.getValue(flavor);
      Optional<ApplePackageConfig> packageConfig =
          config.getPackageConfigForPlatform(platform.getAppleSdk().getApplePlatform());
      packageConfigs.put(
          packageConfig.map(
              applePackageConfig ->
                  ApplePackageConfigAndPlatformInfo.of(applePackageConfig, platform)),
          flavor);
    }

    if (packageConfigs.isEmpty()) {
      return Optional.empty();
    } else if (packageConfigs.keySet().size() == 1) {
      return Iterables.getOnlyElement(packageConfigs.keySet());
    } else {
      throw new HumanReadableException(
          "In target %s: Multi-architecture package has different package configs for targets: %s",
          target.getFullyQualifiedName(), packageConfigs.asMap().values());
    }
  }

  private ImmutableSet<Flavor> getPlatformFlavorsOrDefault(
      BuildTarget target, FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain) {
    Sets.SetView<Flavor> intersection =
        Sets.intersection(appleCxxPlatformFlavorDomain.getFlavors(), target.getFlavors());
    if (intersection.isEmpty()) {
      return ImmutableSet.of(
          toolchainProvider
              .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
              .getDefaultUnresolvedCxxPlatform()
              .getFlavor());
    } else {
      return intersection.immutableCopy();
    }
  }

  private FlavorDomain<AppleCxxPlatform> getAppleCxxPlatformFlavorDomain() {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getAppleCxxPlatforms();
  }
}
