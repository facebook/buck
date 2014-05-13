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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.PrebuiltJar;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class AndroidTransitiveDependencyGraph {


  private final ImmutableSortedSet<BuildRule> rulesToTraverseForTransitiveDeps;

  /**
   * @param deps A set of dependencies for a {@link BuildRule}, presumably one that is in the
   *     process of being constructed via its builder.
   */
  AndroidTransitiveDependencyGraph(ImmutableSortedSet<BuildRule> deps) {
    this.rulesToTraverseForTransitiveDeps = Preconditions.checkNotNull(deps);
  }

  /**
   * @param uberRDotJava that may have produced {@code R.class} files that need to be dexed.
   */
  public AndroidDexTransitiveDependencies findDexDependencies(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps,
      final ImmutableSet<JavaLibrary> buildRulesToExcludeFromDex,
      final UberRDotJava uberRDotJava) {
    // These are paths that will be dex'ed. They may be either directories of compiled .class files,
    // or paths to compiled JAR files.
    final ImmutableSet.Builder<Path> pathsToDexBuilder = ImmutableSet.builder();

    // Paths to the classfiles to not dex.
    final ImmutableSet.Builder<Path> noDxPathsBuilder = ImmutableSet.builder();

    // These are paths to third-party jars that may contain resources that must be included in the
    // final APK.
    final ImmutableSet.Builder<Path> pathsToThirdPartyJarsBuilder = ImmutableSet.builder();

    AndroidResourceDetails details =
        findAndroidResourceDetails(androidResourceDeps);

    // Update pathsToDex.
    final ImmutableSetMultimap<JavaLibrary, Path> classpath =
        Classpaths.getClasspathEntries(rulesToTraverseForTransitiveDeps);
    for (Map.Entry<JavaLibrary, Path> entry : classpath.entries()) {
      if (!buildRulesToExcludeFromDex.contains(entry.getKey())) {
        pathsToDexBuilder.add(entry.getValue());
      } else {
        noDxPathsBuilder.add(entry.getValue());
      }
    }
    // Include the directory of compiled R.java files on the classpath.
    final ImmutableSet<String> rDotJavaPackages = details.rDotJavaPackages;
    if (!rDotJavaPackages.isEmpty()) {
      Path pathToCompiledRDotJavaFiles = uberRDotJava.getPathToCompiledRDotJavaFiles();
      pathsToDexBuilder.add(pathToCompiledRDotJavaFiles);
    }

    ImmutableSet<Path> noDxPaths = noDxPathsBuilder.build();

    // Filter out the classpath entries to exclude from dex'ing, if appropriate
    Set<Path> classpathEntries = Sets.difference(pathsToDexBuilder.build(), noDxPaths);

    Supplier<Map<String, HashCode>> classNamesToHashesSupplier = Suppliers.memoize(
        new Supplier<Map<String, HashCode>>() {
          @Override
          public Map<String, HashCode> get() {
            ImmutableMap.Builder<String, HashCode> builder = ImmutableMap.builder();
            for (JavaLibrary javaLibrary : classpath.keySet()) {
              if (!buildRulesToExcludeFromDex.contains(javaLibrary)) {
                builder.putAll(javaLibrary.getClassNamesToHashes());
              }
            }
            if (!rDotJavaPackages.isEmpty()) {
              builder.putAll(uberRDotJava.getClassNamesToHashes());
            }
            return builder.build();
          }
        });

    // Visit all of the transitive dependencies to populate the above collections.
    new AbstractDependencyVisitor(rulesToTraverseForTransitiveDeps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        // We need to include the transitive closure of the compiled .class files when dex'ing, as
        // well as the third-party jars that they depend on.
        // Update pathsToThirdPartyJars.
        if (rule.getBuildable() instanceof PrebuiltJar) {
          PrebuiltJar prebuiltJar = (PrebuiltJar) rule.getBuildable();
          pathsToThirdPartyJarsBuilder.add(prebuiltJar.getBinaryJar().resolve());
        }
        return maybeVisitAllDeps(rule, rule.getProperties().is(LIBRARY));
      }
    }.start();

    // Classpath entries that should be excluded from dexing should also be excluded from
    // pathsToThirdPartyJars because their resources should not end up in main APK. If they do,
    // the pre-dexed library may try to load a resource from the main APK rather than from within
    // the pre-dexed library (even though the resource is available in both locations). This
    // causes a significant performance regression, as the resource may take more than one second
    // longer to load.
    Set<Path> pathsToThirdPartyJars =
        Sets.difference(pathsToThirdPartyJarsBuilder.build(), noDxPaths);

    return new AndroidDexTransitiveDependencies(
        classpathEntries,
        pathsToThirdPartyJars,
        noDxPaths,
        classNamesToHashesSupplier);
  }

  public AndroidResourceDetails findAndroidResourceDetails(
      ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {
    // This is not part of the AbstractDependencyVisitor traversal because
    // AndroidResourceRule.getAndroidResourceDeps() does a topological sort whereas
    // AbstractDependencyVisitor does only a breadth-first search.
    return new AndroidResourceDetails(androidResourceDeps);
  }

  public AndroidTransitiveDependencies findDependencies() {

    // Paths to assets/ directories that should be included in the final APK.
    final ImmutableSet.Builder<Path> assetsDirectories = ImmutableSet.builder();

    // Paths to native libs directories (often named libs/) that should be included as raw files
    // directories in the final APK.
    final ImmutableSet.Builder<Path> nativeLibsDirectories = ImmutableSet.builder();

    // Paths to native libs directories that are to be treated as assets and so should be included
    // as raw files under /assets/lib/ directory in the APK.
    final ImmutableSet.Builder<Path> nativeLibAssetsDirectories = ImmutableSet.builder();

    final ImmutableSet.Builder<BuildTarget> nativeTargetsWithAssets = ImmutableSet.builder();

    // Path to the module's manifest file
    final ImmutableSet.Builder<Path> manifestFiles = ImmutableSet.builder();

    // Path to the module's proguard_config
    final ImmutableSet.Builder<Path> proguardConfigs = ImmutableSet.builder();

    // Visit all of the transitive dependencies to populate the above collections.
    new AbstractDependencyVisitor(rulesToTraverseForTransitiveDeps) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        // We need to include the transitive closure of the compiled .class files when dex'ing, as
        // well as the third-party jars that they depend on.
        // Update pathsToThirdPartyJars.
        if (rule.getBuildable() instanceof NativeLibraryBuildable) {
          NativeLibraryBuildable nativeLibraryRule = (NativeLibraryBuildable) rule.getBuildable();
          if (nativeLibraryRule.isAsset()) {
            nativeLibAssetsDirectories.add(nativeLibraryRule.getLibraryPath());
            nativeTargetsWithAssets.add(rule.getBuildTarget());
          } else {
            nativeLibsDirectories.add(nativeLibraryRule.getLibraryPath());
          }

          // In the rare event that a PrebuiltNativeLibraryBuildRule has deps, it is likely another
          // NativeLibraryRule that will need to be included in the final APK, so traverse the deps.
          if (rule.getBuildable() instanceof PrebuiltNativeLibrary) {
            return rule.getDeps();
          }
        } else if (rule.getBuildable() instanceof AndroidResource) {
          AndroidResource androidRule = (AndroidResource) rule.getBuildable();
          Path assetsDirectory = androidRule.getAssets();
          if (assetsDirectory != null) {
            assetsDirectories.add(assetsDirectory);
          }
          Path manifestFile = androidRule.getManifestFile();
          if (manifestFile != null) {
            manifestFiles.add(manifestFile);
          }
        } else if (rule.getBuildable() instanceof DefaultJavaLibrary) {
          DefaultJavaLibrary defaultJavaLibrary = (DefaultJavaLibrary) rule.getBuildable();
          Optionals.addIfPresent(defaultJavaLibrary.getProguardConfig(), proguardConfigs);

          if (rule.getBuildable() instanceof AndroidLibrary) {
            AndroidLibrary androidLibraryRule = (AndroidLibrary) rule.getBuildable();
            Optionals.addIfPresent(androidLibraryRule.getManifestFile(), manifestFiles);
          }
        }
        return maybeVisitAllDeps(rule, rule.getProperties().is(LIBRARY));
      }
    }.start();

    return new AndroidTransitiveDependencies(
        nativeLibsDirectories.build(),
        nativeLibAssetsDirectories.build(),
        assetsDirectories.build(),
        nativeTargetsWithAssets.build(),
        manifestFiles.build(),
        proguardConfigs.build());
  }
}
