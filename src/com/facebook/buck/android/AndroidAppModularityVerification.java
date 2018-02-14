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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class AndroidAppModularityVerification extends AbstractBuildRule {
  @AddToRuleKey private final SourcePath appModularityResult;
  @AddToRuleKey private final boolean skipProguard;

  @AddToRuleKey
  private final ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>
      moduleMappedClasspathEntriesForConsistency;

  @AddToRuleKey private final Optional<SourcePath> proguardTextFilesPath;

  private Supplier<SortedSet<BuildRule>> buildDepsSupplier;

  protected AndroidAppModularityVerification(
      SourcePathRuleFinder ruleFinder,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePath appModularityResult,
      boolean skipProguard,
      Optional<SourcePath> proguardTextFilesPath,
      AndroidPackageableCollection packageableCollection) {
    super(buildTarget, projectFilesystem);
    this.appModularityResult = appModularityResult;
    this.skipProguard = skipProguard;
    this.moduleMappedClasspathEntriesForConsistency =
        MoreMaps.convertMultimapToMapOfLists(
            packageableCollection.getModuleMappedClasspathEntriesToDex());
    this.proguardTextFilesPath = proguardTextFilesPath;
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                BuildableSupport.deriveDeps(this, ruleFinder)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    Optional<Path> proguardConfigDir =
        proguardTextFilesPath.map(buildContext.getSourcePathResolver()::getRelativePath);
    Optional<Path> proguardFullConfigFile =
        proguardConfigDir.map(p -> p.resolve("configuration.txt"));
    Optional<Path> proguardMappingFile = proguardConfigDir.map(p -> p.resolve("mapping.txt"));

    ImmutableMultimap<APKModule, Path> additionalDexStoreToJarPathMap =
        moduleMappedClasspathEntriesForConsistency
            .entrySet()
            .stream()
            .flatMap(
                entry ->
                    entry
                        .getValue()
                        .stream()
                        .map(
                            v ->
                                new AbstractMap.SimpleEntry<>(
                                    entry.getKey(),
                                    buildContext.getSourcePathResolver().getAbsolutePath(v))))
            .collect(
                ImmutableListMultimap.toImmutableListMultimap(e -> e.getKey(), e -> e.getValue()));

    AndroidModuleConsistencyStep androidModuleConsistencyStep =
        AndroidModuleConsistencyStep.ensureModuleConsistency(
            buildContext.getSourcePathResolver().getRelativePath(appModularityResult),
            additionalDexStoreToJarPathMap,
            getProjectFilesystem(),
            proguardFullConfigFile,
            proguardMappingFile,
            skipProguard);
    return ImmutableList.of(androidModuleConsistencyStep);
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
