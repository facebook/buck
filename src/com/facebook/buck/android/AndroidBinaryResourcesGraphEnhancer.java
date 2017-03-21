/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;

public class AndroidBinaryResourcesGraphEnhancer {
  public static final Flavor AAPT_PACKAGE_FLAVOR = InternalFlavor.of("aapt_package");
  public static final Flavor RESOURCES_FILTER_FLAVOR = InternalFlavor.of("resources_filter");
  public static final Flavor PACKAGE_STRING_ASSETS_FLAVOR =
      InternalFlavor.of("package_string_assets");

  private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  private final BuildRuleParams buildRuleParams;
  private final boolean includesVectorDrawables;
  private final ImmutableSet<String> locales;
  private final SourcePath manifest;
  private final ManifestEntries manifestEntries;
  private final SourcePathResolver pathResolver;
  private final ResourcesFilter.ResourceCompressionMode resourceCompressionMode;
  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final Optional<String> resourceUnionPackage;
  private final SourcePathRuleFinder ruleFinder;
  private final BuildRuleResolver ruleResolver;
  private final boolean shouldBuildStringSourceMap;
  private final boolean skipCrunchPngs;

  public AndroidBinaryResourcesGraphEnhancer(
      BuildRuleResolver ruleResolver,
      BuildRuleParams buildRuleParams,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      boolean includesVectorDrawables,
      ImmutableSet<String> locales,
      SourcePath manifest,
      ManifestEntries manifestEntries,
      ResourcesFilter.ResourceCompressionMode resourceCompressionMode,
      FilterResourcesStep.ResourceFilter resourceFilter,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs) {
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.pathResolver = new SourcePathResolver(ruleFinder);
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.buildRuleParams = buildRuleParams;
    this.includesVectorDrawables = includesVectorDrawables;
    this.locales = locales;
    this.manifest = manifest;
    this.manifestEntries = manifestEntries;
    this.resourceCompressionMode = resourceCompressionMode;
    this.resourceFilter = resourceFilter;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidResourcesGraphEnhancementResult {
    AaptPackageResources getAaptPackageResources();
    Optional<PackageStringAssets> getPackageStringAssets();
    ImmutableSortedSet<BuildRule> getEnhancedDeps();
  }

  public AndroidResourcesGraphEnhancementResult invoke(
      AndroidPackageableCollection packageableCollection) {
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails();
    ImmutableSortedSet.Builder<BuildRule> enhancedDeps = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    ImmutableCollection<BuildRule> rulesWithResourceDirectories =
        ruleFinder.filterBuildRuleInputs(resourceDetails.getResourceDirectories());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering = resourceFilter.isEnabled() ||
        resourceCompressionMode.isStoreStringsAsAssets() ||
        !locales.isEmpty();

    if (needsResourceFiltering) {
      BuildRuleParams paramsForResourcesFilter =
          buildRuleParams
              .withAppendedFlavor(RESOURCES_FILTER_FLAVOR)
              .copyReplacingDeclaredAndExtraDeps(
                  Suppliers.ofInstance(
                      ImmutableSortedSet.<BuildRule>naturalOrder()
                          .addAll(resourceRules)
                          .addAll(rulesWithResourceDirectories)
                          .build()),
                  Suppliers.ofInstance(ImmutableSortedSet.of()));
      ResourcesFilter resourcesFilter = new ResourcesFilter(
          paramsForResourcesFilter,
          resourceDetails.getResourceDirectories(),
          ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
          locales,
          resourceCompressionMode,
          resourceFilter);
      ruleResolver.addToIndex(resourcesFilter);

      filteredResourcesProvider = resourcesFilter;
      enhancedDeps.add(resourcesFilter);
      resourceRules = ImmutableSortedSet.of(resourcesFilter);
    } else {
      filteredResourcesProvider = new IdentityResourcesProvider(
          resourceDetails.getResourceDirectories().stream()
              .map(pathResolver::getRelativePath)
              .collect(MoreCollectors.toImmutableList()));
    }

    // Create the AaptPackageResourcesBuildable.
    BuildRuleParams paramsForAaptPackageResources = buildRuleParams
        .withAppendedFlavor(AAPT_PACKAGE_FLAVOR)
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of()),
            Suppliers.ofInstance(ImmutableSortedSet.of()));
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        paramsForAaptPackageResources,
        ruleFinder,
        ruleResolver,
        manifest,
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        getTargetsAsRules(resourceDetails.getResourcesWithEmptyResButNonEmptyAssetsDir()),
        packageableCollection.getAssetsDirectories(),
        resourceUnionPackage,
        shouldBuildStringSourceMap,
        skipCrunchPngs,
        includesVectorDrawables,
        bannedDuplicateResourceTypes,
        manifestEntries);
    ruleResolver.addToIndex(aaptPackageResources);
    enhancedDeps.add(aaptPackageResources);

    Optional<PackageStringAssets> packageStringAssets = Optional.empty();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      BuildRuleParams paramsForPackageStringAssets = buildRuleParams
          .withAppendedFlavor(PACKAGE_STRING_ASSETS_FLAVOR)
          .copyReplacingDeclaredAndExtraDeps(
              Suppliers.ofInstance(
                  ImmutableSortedSet.<BuildRule>naturalOrder()
                      .add(aaptPackageResources)
                      .addAll(resourceRules)
                      .addAll(rulesWithResourceDirectories)
                      // Model the dependency on the presence of res directories, which, in the case
                      // of resource filtering, is cached by the `ResourcesFilter` rule.
                      .addAll(
                          Iterables.filter(
                              ImmutableList.of(filteredResourcesProvider),
                              BuildRule.class))
                      .build()),
              Suppliers.ofInstance(ImmutableSortedSet.of()));
      packageStringAssets = Optional.of(
          new PackageStringAssets(
              paramsForPackageStringAssets,
              locales,
              filteredResourcesProvider,
              aaptPackageResources));
      ruleResolver.addToIndex(packageStringAssets.get());
      enhancedDeps.add(packageStringAssets.get());
    }

    return AndroidResourcesGraphEnhancementResult.builder()
        .setAaptPackageResources(aaptPackageResources)
        .setPackageStringAssets(packageStringAssets)
        .setEnhancedDeps(enhancedDeps.build())
        .build();
  }

  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return getTargetsAsRules(targets).stream()
        .map(input -> {
          Preconditions.checkState(input instanceof HasAndroidResourceDeps);
          return (HasAndroidResourceDeps) input;
        })
        .collect(MoreCollectors.toImmutableList());
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(
        buildRuleParams.getBuildTarget(),
        ruleResolver,
        buildTargets);
  }
}
