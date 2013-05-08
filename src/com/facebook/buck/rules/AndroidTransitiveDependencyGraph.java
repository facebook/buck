/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.rules.UberRDotJavaUtil.AndroidResourceDetails;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public class AndroidTransitiveDependencyGraph {

  private AbstractCachingBuildRule buildRule;
  private ImmutableSet<BuildRule> buildRulesToExcludeFromDex;

  public AndroidTransitiveDependencyGraph(
      AbstractCachingBuildRule buildRule,
      ImmutableSet<BuildRule> buildRulesToExcludeFromDex) {
    this.buildRule = buildRule;
    this.buildRulesToExcludeFromDex = buildRulesToExcludeFromDex;
  }

  public AndroidTransitiveDependencies findDependencies(
      ImmutableList<AndroidResourceRule> androidResourceDeps,
      final Optional<BuildContext> context) {

    // These are paths that will be dex'ed. They may be either directories of compiled .class files,
    // or paths to compiled JAR files.
    final ImmutableSet.Builder<String> pathsToDexBuilder = ImmutableSet.builder();

    // Paths to the classfiles to not dex.
    final ImmutableSet.Builder<String> noDxPathsBuilder = ImmutableSet.builder();

    // These are paths to third-party jars that may contain resources that must be included in the
    // final APK.
    final ImmutableSet.Builder<String> pathsToThirdPartyJarsBuilder = ImmutableSet.builder();

    // Paths to assets/ directories that should be included in the final APK.
    final ImmutableSet.Builder<String> assetsDirectories = ImmutableSet.builder();

    // Paths to native libs directories (often named libs/) that should be included as raw files
    // directories in the final APK.
    final ImmutableSet.Builder<String> nativeLibsDirectories = ImmutableSet.builder();

    // Map of BuildRuleType to a list of BuildRules that were not built from cache.
    final ImmutableMultimap.Builder<BuildRuleType, BuildRule> uncachedBuildrules =
        ImmutableMultimap.builder();

    // Path to the module's manifest file
    final ImmutableSet.Builder<String> manifestFiles = ImmutableSet.builder();

    // Path to the module's proguard_config
    final ImmutableSet.Builder<String> proguardConfigs = ImmutableSet.builder();

    // Update pathsToDex.
    for (Map.Entry<BuildRule, String> entry : buildRule.getClasspathEntriesForDeps().entries()) {
      if (!buildRulesToExcludeFromDex.contains(entry.getKey())) {
        pathsToDexBuilder.add(entry.getValue());
      } else {
        noDxPathsBuilder.add(entry.getValue());
      }
    }

    // This is not part of the AbstractDependencyVisitor traversal because
    // AndroidResourceRule.getAndroidResourceDeps() does a topological sort whereas
    // AbstractDependencyVisitor does only a breadth-first search.
    AndroidResourceDetails details =
        new UberRDotJavaUtil.AndroidResourceDetails(androidResourceDeps);

    // Visit all of the transitive dependencies to populate the above collections.
    new AbstractDependencyVisitor(buildRule) {
      @Override
      public boolean visit(BuildRule rule) {
        // We need to include the transitive closure of the compiled .class files when dex'ing, as
        // well as the third-party jars that they depend on.
        // Update pathsToThirdPartyJars.
        if (rule instanceof PrebuiltJarRule) {
          PrebuiltJarRule prebuiltJarRule = (PrebuiltJarRule) rule;
          pathsToThirdPartyJarsBuilder.add(prebuiltJarRule.getBinaryJar());
        } else if (rule instanceof NdkLibraryRule) {
          NdkLibraryRule ndkRule = (NdkLibraryRule) rule;
          nativeLibsDirectories.add(ndkRule.getLibraryPath());
        } else if (rule instanceof AndroidResourceRule) {
          AndroidResourceRule androidRule = (AndroidResourceRule) rule;
          String assetsDirectory = androidRule.getAssets();
          if (assetsDirectory != null) {
            assetsDirectories.add(assetsDirectory);
          }
          String manifestFile = androidRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
        } else if (rule instanceof PrebuiltNativeLibraryBuildRule) {
          PrebuiltNativeLibraryBuildRule androidRule = (PrebuiltNativeLibraryBuildRule) rule;
          String nativeLibsDirectory = androidRule.getNativeLibs();
          if (nativeLibsDirectory != null) {
            nativeLibsDirectories.add(nativeLibsDirectory);
          }
        } else if (rule instanceof AndroidLibraryRule) {
          AndroidLibraryRule androidLibraryRule = (AndroidLibraryRule)rule;
          String proguardConfig = androidLibraryRule.getProguardConfig();
          if (proguardConfig != null) {
            proguardConfigs.add(proguardConfig);
          }
          String manifestFile = androidLibraryRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
        } else if (rule instanceof DefaultJavaLibraryRule) {
          DefaultJavaLibraryRule androidRule = (DefaultJavaLibraryRule)rule;
          String proguardConfig = androidRule.getProguardConfig();
          if (proguardConfig != null) {
            proguardConfigs.add(proguardConfig);
          }
        }
        if (!ruleIsCachedQuiet(rule, context)) {
          uncachedBuildrules.put(rule.getType(), rule);
        }
        // AbstractDependencyVisitor will start from this (AndroidBinaryRule) so make sure it
        // descends to its dependencies even though it is not a library rule.
        return rule.isLibrary() || rule == buildRule;
      }
    }.start();

    // Include the directory of compiled R.java files on the classpath.
    ImmutableSet<String> rDotJavaPackages = details.rDotJavaPackages;
    if (!rDotJavaPackages.isEmpty()) {
      pathsToDexBuilder.add(UberRDotJavaUtil.getPathToCompiledRDotJavaFiles(
          buildRule.getBuildTarget()));
    }

    ImmutableSet<String> noDxPaths = noDxPathsBuilder.build();

    // Filter out the classpath entries to exclude from dex'ing, if appropriate
    Set<String> classpathEntries = Sets.difference(pathsToDexBuilder.build(), noDxPaths);
    // Classpath entries that should be excluded from dexing should also be excluded from
    // pathsToThirdPartyJars because their resources should not end up in main APK. If they do,
    // the pre-dexed library may try to load a resource from the main APK rather than from within
    // the pre-dexed library (even though the resource is available in both locations). This
    // causes a significant performance regression, as the resource may take more than one second
    // longer to load.
    Set<String> pathsToThirdPartyJars =
        Sets.difference(pathsToThirdPartyJarsBuilder.build(), noDxPaths);

    return new AndroidTransitiveDependencies(classpathEntries,
        pathsToThirdPartyJars,
        assetsDirectories.build(),
        nativeLibsDirectories.build(),
        manifestFiles.build(),
        details.resDirectories,
        rDotJavaPackages,
        uncachedBuildrules.build(),
        proguardConfigs.build(),
        noDxPaths);
  }

  private static boolean ruleIsCachedQuiet(BuildRule rule, Optional<BuildContext> context) {
    boolean ruleIsCached = false;
    try {
      if (context.isPresent() && rule.isCached(context.get())) {
        ruleIsCached = true;
      }
    } catch (IOException e) {
      throw new RuntimeException("Invalid State:  By the time we're checking if rules were " +
          "cached in the android_binary rule we should have already successfully checked " +
          "cache for all it's depenencies.");
    }
    return ruleIsCached;
  }
}
