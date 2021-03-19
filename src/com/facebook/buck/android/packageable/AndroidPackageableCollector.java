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

package com.facebook.buck.android.packageable;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection.ResourceDetails;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AndroidPackageableCollector {

  private final ImmutableAndroidPackageableCollection.Builder collectionBuilder =
      ImmutableAndroidPackageableCollection.builder();

  private final Map<APKModule, ResourceCollector> resourceCollectors = new HashMap<>();

  // Map is used instead of ImmutableMap.Builder for its containsKey() method.
  private final Map<String, BuildConfigFields> buildConfigs = new HashMap<>();

  private final ImmutableSet.Builder<HasJavaClassHashes> javaClassHashesProviders =
      ImmutableSet.builder();

  private final BuildTarget collectionRoot;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final ImmutableSet<BuildTarget> resourcesToExclude;
  private final ImmutableCollection<SourcePath> nativeLibsToExclude;
  private final ImmutableCollection<NativeLinkableGroup> nativeLinkablesToExcludeGroup;
  private final ImmutableCollection<SourcePath> nativeLibAssetsToExclude;
  private final ImmutableCollection<NativeLinkableGroup> nativeLinkablesAssetsToExcludeGroup;
  private final APKModuleGraph apkModuleGraph;
  private final AndroidPackageableFilter androidPackageableFilter;

  @VisibleForTesting
  public AndroidPackageableCollector(BuildTarget collectionRoot) {
    this(collectionRoot, ImmutableSet.of(), new APKModuleGraph(TargetGraph.EMPTY, collectionRoot));
  }

  public AndroidPackageableCollector(
      BuildTarget collectionRoot,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      APKModuleGraph apkModuleGraph) {
    this(
        collectionRoot,
        buildTargetsToExcludeFromDex,
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        apkModuleGraph,
        new NoopAndroidPackageableFilter());
  }

  public AndroidPackageableCollector(
      BuildTarget collectionRoot,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      APKModuleGraph apkModuleGraph,
      AndroidPackageableFilter androidPackageableFilter) {
    this(
        collectionRoot,
        buildTargetsToExcludeFromDex,
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        apkModuleGraph,
        androidPackageableFilter);
  }

  /**
   * @param resourcesToExclude Only relevant to {@link
   *     com.facebook.buck.android.AndroidInstrumentationApk} which needs to remove resources that
   *     are already included in the
   *     com.facebook.buck.android.AndroidInstrumentationApkDescription.AbstractAndroidInstrumentationApkDescriptionArg#apk.
   *     The same goes for native libs and native linkables, and their asset counterparts.
   */
  public AndroidPackageableCollector(
      BuildTarget collectionRoot,
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex,
      ImmutableSet<BuildTarget> resourcesToExclude,
      ImmutableCollection<SourcePath> nativeLibsToExclude,
      ImmutableCollection<NativeLinkableGroup> nativeLinkablesToExcludeGroup,
      ImmutableCollection<SourcePath> nativeLibAssetsToExclude,
      ImmutableCollection<NativeLinkableGroup> nativeLinkableGroupAssetsToExclude,
      APKModuleGraph apkModuleGraph,
      AndroidPackageableFilter androidPackageableFilter) {
    this.collectionRoot = collectionRoot;
    this.buildTargetsToExcludeFromDex = buildTargetsToExcludeFromDex;
    this.resourcesToExclude = resourcesToExclude;
    this.nativeLibsToExclude = nativeLibsToExclude;
    this.nativeLinkablesToExcludeGroup = nativeLinkablesToExcludeGroup;
    this.nativeLibAssetsToExclude = nativeLibAssetsToExclude;
    this.nativeLinkablesAssetsToExcludeGroup = nativeLinkableGroupAssetsToExclude;
    this.apkModuleGraph = apkModuleGraph;
    this.androidPackageableFilter = androidPackageableFilter;
    apkModuleGraph
        .getAPKModules()
        .forEach(module -> resourceCollectors.put(module, new ResourceCollector()));
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
    return FluentIterable.from(rules).filter(AndroidPackageable.class);
  }

  public AndroidPackageableCollector addStringWhitelistedResourceDirectory(
      BuildTarget owner, SourcePath resourceDir) {
    if (resourcesToExclude.contains(owner)
        || androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return this;
    }

    ResourceCollector collector = getResourceCollector(owner);
    collector.addWhitelistedStringDirectories(resourceDir);
    collector.doAddResourceDirectory(owner, resourceDir);
    return this;
  }

  public AndroidPackageableCollector addResourceDirectory(
      BuildTarget owner, SourcePath resourceDir) {
    if (resourcesToExclude.contains(owner)
        || androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return this;
    }
    ResourceCollector collector = getResourceCollector(owner);
    collector.doAddResourceDirectory(owner, resourceDir);
    return this;
  }

  public AndroidPackageableCollector addNativeLibsDirectory(
      BuildTarget owner, SourcePath nativeLibDir) {
    if (nativeLibsToExclude.contains(nativeLibDir)) {
      return this;
    }
    APKModule module = apkModuleGraph.findModuleForTarget(owner);
    if (module.isRootModule()) {
      collectionBuilder.putNativeLibsDirectories(module, nativeLibDir);
    } else {
      collectionBuilder.putNativeLibAssetsDirectories(module, nativeLibDir);
    }
    return this;
  }

  /**
   * Add a directory containing native libraries that must be packaged in a standard location so
   * that they are accessible via the system library loader.
   */
  public AndroidPackageableCollector addNativeLibsDirectoryForSystemLoader(
      BuildTarget owner, SourcePath nativeLibDir) {
    if (nativeLibsToExclude.contains(nativeLibDir)) {
      return this;
    }
    APKModule module = apkModuleGraph.findModuleForTarget(owner);
    if (module.isRootModule()) {
      collectionBuilder.addNativeLibsDirectoriesForSystemLoader(nativeLibDir);
    } else {
      throw new HumanReadableException(
          "%s which is marked as use_system_library_loader cannot be included in non-root-module %s",
          owner, module.getName());
    }
    return this;
  }

  public AndroidPackageableCollector addNativeLinkable(NativeLinkableGroup nativeLinkableGroup) {
    if (nativeLinkablesToExcludeGroup.contains(nativeLinkableGroup)) {
      return this;
    }
    APKModule module = apkModuleGraph.findModuleForTarget(nativeLinkableGroup.getBuildTarget());
    if (module.isRootModule()) {
      collectionBuilder.putNativeLinkables(module, nativeLinkableGroup);
    } else {
      collectionBuilder.putNativeLinkablesAssets(module, nativeLinkableGroup);
    }
    return this;
  }

  public AndroidPackageableCollector addNativeLinkableAsset(
      NativeLinkableGroup nativeLinkableGroup) {
    if (nativeLinkablesAssetsToExcludeGroup.contains(nativeLinkableGroup)) {
      return this;
    }
    APKModule module = apkModuleGraph.findModuleForTarget(nativeLinkableGroup.getBuildTarget());
    collectionBuilder.putNativeLinkablesAssets(module, nativeLinkableGroup);
    return this;
  }

  public AndroidPackageableCollector addNativeLibAssetsDirectory(
      BuildTarget owner, SourcePath assetsDir) {
    if (nativeLibAssetsToExclude.contains(assetsDir)) {
      return this;
    }
    // We need to build the native target in order to have the assets available still.
    APKModule module = apkModuleGraph.findModuleForTarget(owner);
    collectionBuilder.putNativeLibAssetsDirectories(module, assetsDir);
    return this;
  }

  public AndroidPackageableCollector addAssetsDirectory(
      BuildTarget owner, SourcePath assetsDirectory) {
    if (resourcesToExclude.contains(owner)
        || androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return this;
    }

    ResourceCollector collector = getResourceCollector(owner);
    collector.addResourcesWithAssets(owner);

    APKModule module = apkModuleGraph.findResourceModuleForTarget(owner);
    collectionBuilder.putAssetsDirectories(module, assetsDirectory);
    return this;
  }

  public AndroidPackageableCollector addProguardConfig(
      BuildTarget owner, SourcePath proguardConfig) {
    if (!buildTargetsToExcludeFromDex.contains(owner)
        && !androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      collectionBuilder.addProguardConfigs(proguardConfig);
    }
    return this;
  }

  public AndroidPackageableCollector addClasspathEntry(
      HasJavaClassHashes hasJavaClassHashes, SourcePath classpathEntry) {
    BuildTarget target = hasJavaClassHashes.getBuildTarget();
    if (androidPackageableFilter.shouldExcludeNonNativeTarget(target)) {
      return this;
    }
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

  /**
   * add a manifest piece associated with the target owner. If part of a module the manifest pieces
   * will be included in both the module manifest and the base apk manifest
   *
   * @param owner target that owns the manifest piece
   * @param manifest the sourcepath to the manifest piece
   * @return this
   */
  public AndroidPackageableCollector addManifestPiece(BuildTarget owner, SourcePath manifest) {
    if (androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return this;
    }
    collectionBuilder.putAndroidManifestPieces(
        apkModuleGraph.findManifestModuleForTarget(owner), manifest);
    return this;
  }

  public AndroidPackageableCollector addPathToThirdPartyJar(
      BuildTarget owner, SourcePath pathToThirdPartyJar) {
    if (androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return this;
    }
    if (buildTargetsToExcludeFromDex.contains(owner)) {
      collectionBuilder.addNoDxClasspathEntries(pathToThirdPartyJar);
    } else {
      collectionBuilder.putPathsToThirdPartyJars(
          apkModuleGraph.findModuleForTarget(owner), pathToThirdPartyJar);
    }
    return this;
  }

  public void addBuildConfig(BuildTarget owner, String javaPackage, BuildConfigFields constants) {
    if (androidPackageableFilter.shouldExcludeNonNativeTarget(owner)) {
      return;
    }
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
        javaClassProviders.stream()
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

    apkModuleGraph
        .getAPKModules()
        .forEach(
            apkModule ->
                collectionBuilder.putResourceDetails(
                    apkModule, resourceCollectors.get(apkModule).getResourceDetails()));
    return collectionBuilder.build();
  }

  private ResourceCollector getResourceCollector(BuildTarget owner) {
    APKModule module = apkModuleGraph.findResourceModuleForTarget(owner);
    ResourceCollector collector = resourceCollectors.get(module);
    if (collector == null) {
      throw new RuntimeException(
          String.format(
              "Unable to find collector for target %s in module %s",
              owner.getFullyQualifiedName(), module.getName()));
    }
    return collector;
  }

  private class ResourceCollector {
    private final ImmutableAndroidPackageableCollection.ResourceDetails.Builder
        resourceDetailsBuilder = ImmutableAndroidPackageableCollection.ResourceDetails.builder();

    private final ImmutableList.Builder<BuildTarget> resourcesWithNonEmptyResDir =
        ImmutableList.builder();
    private final ImmutableList.Builder<BuildTarget> resourcesWithAssets = ImmutableList.builder();
    private final ImmutableList.Builder<SourcePath> resourceDirectories = ImmutableList.builder();

    public void addWhitelistedStringDirectories(SourcePath resourceDir) {
      resourceDetailsBuilder.addWhitelistedStringDirectories(resourceDir);
    }

    public ResourceDetails getResourceDetails() {
      ImmutableSet<BuildTarget> resources =
          ImmutableSet.copyOf(resourcesWithNonEmptyResDir.build());
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

      return resourceDetailsBuilder.build();
    }

    public void addResourcesWithAssets(BuildTarget owner) {
      resourcesWithAssets.add(owner);
    }

    public void doAddResourceDirectory(BuildTarget owner, SourcePath resourceDir) {
      resourcesWithNonEmptyResDir.add(owner);
      resourceDirectories.add(resourceDir);
    }
  }
}
