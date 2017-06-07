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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class ApplePackageDescription
    implements Description<ApplePackageDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            ApplePackageDescription.AbstractApplePackageDescriptionArg> {

  private final CxxPlatform defaultCxxPlatform;
  private final AppleConfig config;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;

  public ApplePackageDescription(
      AppleConfig config,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain) {
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.config = config;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ApplePackageDescriptionArg args)
      throws NoSuchBuildTargetException {
    final BuildRule bundle =
        resolver.getRule(propagateFlavorsToTarget(params.getBuildTarget(), args.getBundle()));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    final Optional<ApplePackageConfigAndPlatformInfo> applePackageConfigAndPlatformInfo =
        getApplePackageConfig(
            params.getBuildTarget(),
            MacroArg.toMacroArgFunction(
                AbstractGenruleDescription.PARSE_TIME_MACRO_HANDLER,
                params.getBuildTarget(),
                cellRoots,
                resolver));
    if (applePackageConfigAndPlatformInfo.isPresent()) {
      return new ExternallyBuiltApplePackage(
          params.copyReplacingExtraDeps(
              () ->
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .add(bundle)
                      .addAll(
                          applePackageConfigAndPlatformInfo
                              .get()
                              .getExpandedArg()
                              .getDeps(ruleFinder))
                      .build()),
          applePackageConfigAndPlatformInfo.get(),
          Preconditions.checkNotNull(bundle.getSourcePathToOutput()),
          bundle.isCacheable());
    } else {
      return new BuiltinApplePackage(params, bundle);
    }
  }

  @Override
  public Class<ApplePackageDescriptionArg> getConstructorArgType() {
    return ApplePackageDescriptionArg.class;
  }

  private BuildTarget propagateFlavorsToTarget(BuildTarget fromTarget, BuildTarget toTarget) {
    return BuildTarget.builder(toTarget).addAllFlavors(fromTarget.getFlavors()).build();
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
    addDepsFromParam(extraDepsBuilder, targetGraphOnlyDepsBuilder, buildTarget, cellRoots);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(appleCxxPlatformFlavorDomain));
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
  private Optional<ApplePackageConfigAndPlatformInfo> getApplePackageConfig(
      BuildTarget target, Function<String, com.facebook.buck.rules.args.Arg> macroExpander) {
    Set<Flavor> platformFlavors = getPlatformFlavorsOrDefault(target);

    // Ensure that different platforms generate the same config.
    // The value of this map is just for error reporting.
    Multimap<Optional<ApplePackageConfigAndPlatformInfo>, Flavor> packageConfigs =
        MultimapBuilder.hashKeys().arrayListValues().build();

    for (Flavor flavor : platformFlavors) {
      AppleCxxPlatform platform = appleCxxPlatformFlavorDomain.getValue(flavor);
      Optional<ApplePackageConfig> packageConfig =
          config.getPackageConfigForPlatform(platform.getAppleSdk().getApplePlatform());
      packageConfigs.put(
          packageConfig.isPresent()
              ? Optional.of(
                  ApplePackageConfigAndPlatformInfo.of(
                      packageConfig.get(), macroExpander, platform))
              : Optional.empty(),
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

  /**
   * Retrieve deps from macros in externally configured rules.
   *
   * <p>This is used for ImplicitDepsInferringDescription, so it is flavor agnostic.
   */
  private void addDepsFromParam(
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder,
      BuildTarget target,
      CellPathResolver cellNames) {
    // Add all macro expanded dependencies for these platforms.
    for (Flavor flavor : appleCxxPlatformFlavorDomain.getFlavors()) {
      AppleCxxPlatform platform = appleCxxPlatformFlavorDomain.getValue(flavor);
      Optional<ApplePackageConfig> packageConfig =
          config.getPackageConfigForPlatform(platform.getAppleSdk().getApplePlatform());

      if (packageConfig.isPresent()) {
        try {
          AbstractGenruleDescription.PARSE_TIME_MACRO_HANDLER.extractParseTimeDeps(
              target,
              cellNames,
              packageConfig.get().getCommand(),
              buildDepsBuilder,
              targetGraphOnlyDepsBuilder);
        } catch (MacroException e) {
          throw new HumanReadableException(
              e,
              "%s (for platform %s): %s",
              target,
              platform.getAppleSdk().getApplePlatform().getName(),
              e.getMessage());
        }
      }
    }
  }

  private ImmutableSet<Flavor> getPlatformFlavorsOrDefault(BuildTarget target) {
    Sets.SetView<Flavor> intersection =
        Sets.intersection(appleCxxPlatformFlavorDomain.getFlavors(), target.getFlavors());
    if (intersection.isEmpty()) {
      return ImmutableSet.of(defaultCxxPlatform.getFlavor());
    } else {
      return intersection.immutableCopy();
    }
  }
}
