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

import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDefaultPlatform;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
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
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
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

    Optional<ImmutableApplePackageConfigAndPlatformInfo> applePackageConfigAndPlatformInfo =
        getApplePackageConfig(buildTarget, args.getDefaultPlatform(), graphBuilder);

    if (applePackageConfigAndPlatformInfo.isPresent()) {
      return new ExternallyBuiltApplePackage(
          buildTarget,
          projectFilesystem,
          sandboxExecutionStrategy,
          graphBuilder,
          applePackageConfigAndPlatformInfo.get(),
          Objects.requireNonNull(bundle.getSourcePathToOutput()),
          bundle.isCacheable(),
          Optional.empty(),
          args.isNeedAndroidTools()
              ? Optional.of(
                  AndroidTools.getAndroidTools(
                      toolchainProvider, buildTarget.getTargetConfiguration()))
              : Optional.empty());
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
    return toTarget.withAppendedFlavors(fromTarget.getFlavors().getSet());
  }

  /** Propagate the packages's flavors to its dependents. */
  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractApplePackageDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.isNeedAndroidTools()) {
      AndroidTools.addParseTimeDepsToAndroidTools(
          toolchainProvider, buildTarget, targetGraphOnlyDepsBuilder);
    }
    extraDepsBuilder.add(propagateFlavorsToTarget(buildTarget, constructorArg.getBundle()));
    getAppleCxxPlatformsFlavorDomain(buildTarget.getTargetConfiguration())
        .getValues()
        .forEach(
            platform ->
                targetGraphOnlyDepsBuilder.addAll(
                    platform.getParseTimeDeps(buildTarget.getTargetConfiguration())));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(getAppleCxxPlatformsFlavorDomain(toolchainTargetConfiguration)));
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return true;
  }

  @RuleArg
  interface AbstractApplePackageDescriptionArg extends BuildRuleArg, HasDefaultPlatform {
    @Hint(isDep = false)
    BuildTarget getBundle();

    /** This argument allows to specify if it needs android tools (like dex, aapt, ndk, sdk). */
    @Value.Default
    default boolean isNeedAndroidTools() {
      return false;
    }
  }

  /**
   * Get the correct package configuration based on the platform flavors of this build target.
   *
   * <p>Validates that all named platforms yields the identical package config.
   *
   * @return If found, a package config for this target.
   * @throws HumanReadableException if there are multiple possible package configs.
   */
  private Optional<ImmutableApplePackageConfigAndPlatformInfo> getApplePackageConfig(
      BuildTarget target, Optional<Flavor> defaultPlatform, ActionGraphBuilder graphBuilder) {
    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain =
        getAppleCxxPlatformsFlavorDomain(target.getTargetConfiguration());
    Set<Flavor> platformFlavors =
        getPlatformFlavorsOrDefault(target, defaultPlatform, appleCxxPlatformFlavorDomain);

    // Ensure that different platforms generate the same config.
    // The value of this map is just for error reporting.
    Multimap<Optional<ImmutableApplePackageConfigAndPlatformInfo>, Flavor> packageConfigs =
        MultimapBuilder.hashKeys().arrayListValues().build();

    for (Flavor flavor : platformFlavors) {
      AppleCxxPlatform platform =
          appleCxxPlatformFlavorDomain.getValue(flavor).resolve(graphBuilder);
      Optional<AppleConfig.ApplePackageConfig> packageConfig =
          config.getPackageConfigForPlatform(platform.getAppleSdk().getApplePlatform());
      packageConfigs.put(
          packageConfig.map(
              applePackageConfig ->
                  ImmutableApplePackageConfigAndPlatformInfo.of(applePackageConfig, platform)),
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
      BuildTarget target,
      Optional<Flavor> defaultPlatform,
      FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatformFlavorDomain) {

    Sets.SetView<Flavor> intersection =
        Sets.intersection(appleCxxPlatformFlavorDomain.getFlavors(), target.getFlavors().getSet());
    if (intersection.isEmpty()) {
      if (defaultPlatform.isPresent()) {
        return ImmutableSet.of(defaultPlatform.get());
      }
      return ImmutableSet.of(
          toolchainProvider
              .getByName(
                  CxxPlatformsProvider.DEFAULT_NAME,
                  target.getTargetConfiguration(),
                  CxxPlatformsProvider.class)
              .getDefaultUnresolvedCxxPlatform()
              .getFlavor());
    } else {
      return intersection.immutableCopy();
    }
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
}
