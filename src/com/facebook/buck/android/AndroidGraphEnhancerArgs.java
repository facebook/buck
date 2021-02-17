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

package com.facebook.buck.android;

import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.description.arg.HasApplicationModuleBlacklist;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.immutables.value.Value;

public interface AndroidGraphEnhancerArgs
    extends HasDuplicateAndroidResourceTypes, HasApplicationModuleBlacklist {
  Optional<SourcePath> getManifest();

  Optional<SourcePath> getManifestSkeleton();

  Optional<SourcePath> getModuleManifestSkeleton();

  Optional<String> getPackageType();

  @Hint(isDep = false)
  ImmutableSet<BuildTarget> getNoDx();

  @Value.Default
  default boolean getDisablePreDex() {
    return false;
  }

  Optional<ProGuardObfuscateStep.SdkProguardType> getAndroidSdkProguardConfig();

  @Value.Default
  default int getOptimizationPasses() {
    return ProGuardObfuscateStep.DEFAULT_OPTIMIZATION_PASSES;
  }

  List<String> getProguardJvmArgs();

  Optional<SourcePath> getProguardConfig();

  @Value.Default
  default ResourceCompressionMode getResourceCompression() {
    return ResourceCompressionMode.DISABLED;
  }

  Optional<Boolean> isSkipCrunchPngs();

  @Value.Default
  default boolean isIncludesVectorDrawables() {
    return false;
  }

  @Value.Default
  default boolean isNoAutoVersionResources() {
    return false;
  }

  @Value.Default
  default boolean isNoVersionTransitionsResources() {
    return false;
  }

  @Value.Default
  default boolean isNoAutoAddOverlayResources() {
    return false;
  }

  Set<BuildTarget> getApplicationModuleTargets();

  ImmutableMap<String, ImmutableList<BuildTarget>> getApplicationModuleConfigs();

  @Value.Default
  default Set<String> getApplicationModulesWithResources() {
    return ImmutableSet.of();
  }

  @Value.Default
  default Set<String> getApplicationModulesWithManifest() {
    return ImmutableSet.of();
  }

  Optional<ImmutableMap<String, ImmutableList<String>>> getApplicationModuleDependencies();

  @Value.Default
  default boolean getIsCacheable() {
    return true;
  }

  ImmutableList<String> getAdditionalAaptParams();

  @Value.Default
  default AaptMode getAaptMode() {
    return AaptMode.AAPT1;
  }

  @Value.Default
  default boolean isTrimResourceIds() {
    return false;
  }

  @Value.Default
  default boolean isAllowRDotJavaInSecondaryDex() {
    return false;
  }

  Optional<String> getKeepResourcePattern();

  Optional<String> getResourceUnionPackage();

  ImmutableSet<String> getLocales();

  /** Whether to filter locales using aapt2. */
  @Value.Default
  default boolean isAapt2LocaleFiltering() {
    return false;
  }

  Optional<String> getLocalizedStringFileName();

  @Value.Default
  default boolean isBuildStringSourceMap() {
    return false;
  }

  @Value.Default
  default boolean isIgnoreAaptProguardConfig() {
    return false;
  }

  Set<TargetCpuType> getCpuFilters();

  Optional<StringWithMacros> getPreprocessJavaClassesBash();

  @Value.Default
  default boolean isReorderClassesIntraDex() {
    return false;
  }

  @Value.Default
  default String getDexTool() {
    return DxStep.D8;
  }

  Optional<SourcePath> getDexReorderToolFile();

  Optional<SourcePath> getDexReorderDataDumpFile();

  Map<String, List<Pattern>> getNativeLibraryMergeMap();

  Optional<BuildTarget> getNativeLibraryMergeGlue();

  Optional<BuildTarget> getNativeLibraryMergeCodeGenerator();

  Optional<ImmutableSortedSet<String>> getNativeLibraryMergeLocalizedSymbols();

  Optional<BuildTarget> getNativeLibraryProguardConfigGenerator();

  @Value.Default
  default boolean isEnableRelinker() {
    return false;
  }

  ImmutableList<Pattern> getRelinkerWhitelist();

  @Value.Default
  default ManifestEntries getManifestEntries() {
    return ManifestEntries.empty();
  }

  @Value.Default
  default BuildConfigFields getBuildConfigValues() {
    return BuildConfigFields.of();
  }

  Optional<StringWithMacros> getPostFilterResourcesCmd();

  Optional<SourcePath> getBuildConfigValuesFile();

  @Value.Default
  default boolean isSkipProguard() {
    return false;
  }

  @Value.Default
  default boolean getUseDynamicFeature() {
    return false;
  }

  @Value.Default
  default ImmutableSet<String> getExtraFilteredResources() {
    return ImmutableSet.of();
  }
}
