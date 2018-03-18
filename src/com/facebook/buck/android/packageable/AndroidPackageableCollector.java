/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.packageable;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection.ResourceDetails;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AndroidPackageableCollector {

  private final AndroidPackageableCollection.Builder collectionBuilder =
      AndroidPackageableCollection.builder();

  private final ResourceDetails.Builder resourceDetailsBuilder = ResourceDetails.builder();

  private final ImmutableList.Builder<BuildTarget> resourcesWithNonEmptyResDir =
      ImmutableList.builder();
  private final ImmutableList.Builder<BuildTarget> resourcesWithAssets = ImmutableList.builder();
  private final ImmutableList.Builder<SourcePath> resourceDirectories = ImmutableList.builder();

  // Map is used instead of ImmutableMap.Builder for its containsKey() method.
  private final Map<String, BuildConfigFields> buildConfigs = new HashMap<>();

  private final ImmutableSet.Builder<HasJavaClassHashes> javaClassHashesProviders =
      ImmutableSet.builder();

  private final BuildTarget collectionRoot;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final APKModuleGraph apkModuleGraph;

  @VisibleForTesting
  public AndroidPackageableCollector(BuildTarget collectionRoot) {
    this(
        collectionRoot,
        ImmutableSet.of(),
        ImmutableSet.of(),
        new APKModuleGraph(TargetGraph.EMPTY, collectionRoot, Optional.empty()));
  }

  /**
   * @param resourcesToExclude Only relevant to {@link AndroidInstrumentationApk} which needs to
   *     remove resources that are already included in the {@link
   *     AndroidInstrumentationApkDescription.AndroidInstrumentationApkDescriptionArg#apk}
   */
  public AndroidPackageableCollector(
      BuildTarget collectionRoot,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      APKModuleGraph apkModuleGraph) {
    this.collectionRoot = collectionRoot;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.apkModuleGraph = apkModuleGraph;
  }

  /** Add packageables */
  public void addPackageables(
      Iterable<AndroidPackageable> packageables, BuildRuleResolver ruleResolver) {
    Set<AndroidPackageable> explored = new HashSet<>();

    for (AndroidPackageable packageable : packageables) {
      postOrderTraverse(packageable, explored, ruleResolver);
    }
  }

  private void postOrderTraverse(
      AndroidPackageable packageable,
      Set<AndroidPackageable> explored,
      BuildRuleResolver ruleResolver) {
    if (explored.contains(packageable)) {
      return;
    }
    explored.add(packageable);

    for (AndroidPackageable dep : packageable.getRequiredPackageables(ruleResolver)) {
      postOrderTraverse(dep, explored, ruleResolver);
    }
    packageable.addToCollector(this);
  }

  /**
   * Returns all {@link BuildRule}s of the given rules that are {@link AndroidPackageable}. Helper
   * for implementations of AndroidPackageable that just want to return all of their packagable
   * dependencies.
   */
  public static Iterable<AndroidPackageable> getPackageableRules(Iterable<BuildRule> rules) {
    return FluentIterable.from(rules).filter(AndroidPackageable.class).toList();
  }

  public AndroidPackageableCollector addStringWhitelistedResourceDirectory(
      BuildTarget owner, SourcePath resourceDir) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    resourceDetailsBuilder.addWhitelistedStringDirectories(resourceDir);
    doAddResourceDirectory(owner, resourceDir);
    return this;
  }

  public AndroidPackageableCollector addResourceDirectory(
      BuildTarget owner, SourcePath resourceDir) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    doAddResourceDirectory(owner, resourceDir);
    return this;
  }

  private void doAddResourceDirectory(BuildTarget owner, SourcePath resourceDir) {
    resourcesWithNonEmptyResDir.add(owner);
    resourceDirectories.add(resourceDir);
  }

  public AndroidPackageableCollector addNativeLibsDirectory(
      BuildTarget owner, SourcePath nativeLibDir) {
    APKModule module = apkModuleGraph.findModuleForTarget(owner);
    if (module.isRootModule()) {
      collectionBuilder.putNativeLibsDirectories(module, nativeLibDir);
    } else {
      collectionBuilder.putNativeLibAssetsDirectories(module, nativeLibDir);
    }
    return this;
  }

  public AndroidPackageableCollector addNativeLinkable(NativeLinkable nativeLinkable) {
    APKModule module = apkModuleGraph.findModuleForTarget(nativeLinkable.getBuildTarget());
    if (module.isRootModule()) {
      collectionBuilder.putNativeLinkables(module, nativeLinkable);
    } else {
      collectionBuilder.putNativeLinkablesAssets(module, nativeLinkable);
    }
    return this;
  }

  public AndroidPackageableCollector addNativeLinkableAsset(NativeLinkable nativeLinkable) {
    APKModule module = apkModuleGraph.findModuleForTarget(nativeLinkable.getBuildTarget());
    collectionBuilder.putNativeLinkablesAssets(module, nativeLinkable);
    return this;
  }

  public AndroidPackageableCollector addNativeLibAssetsDirectory(
      BuildTarget owner, SourcePath assetsDir) {
    // We need to build the native target in order to have the assets available still.
    APKModule module = apkModuleGraph.findModuleForTarget(owner);
    collectionBuilder.putNativeLibAssetsDirectories(module, assetsDir);
    return this;
  }

  public AndroidPackageableCollector addAssetsDirectory(
      BuildTarget owner, SourcePath assetsDirectory) {
    if (resourcesToExclude.contains(owner)) {
      return this;
    }

    resourcesWithAssets.add(owner);
    collectionBuilder.addAssetsDirectories(assetsDirectory);
    return this;
  }

  public AndroidPackageableCollector addProguardConfig(
      BuildTarget owner, SourcePath proguardConfig) {
    if (!buildTargetsToExcludeFromDex.contains(owner)) {
      collectionBuilder.addProguardConfigs(proguardConfig);
    }
    return this;
  }

  public AndroidPackageableCollector addClasspathEntry(
      HasJavaClassHashes hasJavaClassHashes, SourcePath classpathEntry) {
    BuildTarget target = hasJavaClassHashes.getBuildTarget();
    if (buildTargetsToExcludeFromDex.contains(target)) {
      collectionBuilder.addNoDxClasspathEntries(classpathEntry);
    } else {
      collectionBuilder.addClasspathEntriesToDex(classpathEntry);
      APKModule module = apkModuleGraph.findModuleForTarget(target);
      collectionBuilder.putModuleMappedClasspathEntriesToDex(module, classpathEntry);
      javaClassHashesProviders.add(hasJavaClassHashes);
    }
    return this;
  }

  public AndroidPackageableCollector addManifestPiece(SourcePath manifest) {
    collectionBuilder.addAndroidManifestPieces(manifest);
    return this;
  }

  public AndroidPackageableCollector addPathToThirdPartyJar(
      BuildTarget owner, SourcePath pathToThirdPartyJar) {
    if (buildTargetsToExcludeFromDex.contains(owner)) {
      collectionBuilder.addNoDxClasspathEntries(pathToThirdPartyJar);
    } else {
      collectionBuilder.addPathsToThirdPartyJars(pathToThirdPartyJar);
    }
    return this;
  }

  public void addBuildConfig(String javaPackage, BuildConfigFields constants) {
    if (buildConfigs.containsKey(javaPackage)) {
      throw new HumanReadableException(
          "Multiple android_build_config() rules with the same package %s in the "
              + "transitive deps of %s.",
          javaPackage, collectionRoot);
    }
    buildConfigs.put(javaPackage, constants);
  }

  public AndroidPackageableCollection build() {
    collectionBuilder.setBuildConfigs(ImmutableMap.copyOf(buildConfigs));
    ImmutableSet<HasJavaClassHashes> javaClassProviders = javaClassHashesProviders.build();
    collectionBuilder.addAllJavaLibrariesToDex(
        javaClassProviders
            .stream()
            .map(HasJavaClassHashes::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet()));
    collectionBuilder.setClassNamesToHashesSupplier(
        MoreSuppliers.memoize(
                () -> {
                  Builder<String, HashCode> builder = ImmutableMap.builder();
                  for (HasJavaClassHashes hasJavaClassHashes : javaClassProviders) {
                    builder.putAll(hasJavaClassHashes.getClassNamesToHashes());
                  }
                  return builder.build();
                })
            ::get);

    ImmutableSet<BuildTarget> resources = ImmutableSet.copyOf(resourcesWithNonEmptyResDir.build());
    for (BuildTarget buildTarget : resourcesWithAssets.build()) {
      if (!resources.contains(buildTarget)) {
        resourceDetailsBuilder.addResourcesWithEmptyResButNonEmptyAssetsDir(buildTarget);
      }
    }

    // Reverse the resource directories/targets collections because we perform a post-order
    // traversal of the action graph, and we need to return these collections topologically
    // sorted.
    resourceDetailsBuilder.setResourceDirectories(
        resourceDirectories.build().reverse().stream().distinct().collect(Collectors.toList()));
    resourceDetailsBuilder.setResourcesWithNonEmptyResDir(
        resourcesWithNonEmptyResDir.build().reverse());

    collectionBuilder.setResourceDetails(resourceDetailsBuilder.build());
    return collectionBuilder.build();
  }
}
