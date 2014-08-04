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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Map;

/**
 * A collection of Android content that should be included in an Android package (apk or aar).
 */
public class AndroidPackageableCollection {

  public static class ResourceDetails {

    /**
     * A list of "res" directories that should be passed to the aapt command to build the APK,
     * sorted topologically.
     */
    public final ImmutableList<Path> resourceDirectories;

    /**
     * A set of "res" directories that contain "whitelisted" strings, i.e. the strings-as-assets
     * resource filter does not affect these directories.
     */
    public final ImmutableSet<Path> whitelistedStringDirectories;

    /**
     * Package names of all the transitive android resources.
     */
    public final Supplier<ImmutableSet<String>> rDotJavaPackagesSupplier;

    /**
     * Whether the set returned by {@link #rDotJavaPackagesSupplier} will be empty. This can be
     * queried before the {@link #rDotJavaPackagesSupplier} is determined.
     */
    public final boolean hasRDotJavaPackages;

    /**
     * A list of build targets belonging to {@link com.facebook.buck.android.AndroidResource}s with
     * non-empty "res" directory, sorted topologically. Note that these are {@link BuildTarget}s
     * to avoid introducing a circular dependency.
     */
    public final ImmutableList<BuildTarget> resourcesWithNonEmptyResDir;

    /**
     * Unlike {@link #resourcesWithNonEmptyResDir}, these resources only contain "assets".
     */
    public final ImmutableList<BuildTarget> resourcesWithEmptyResButNonEmptyAssetsDir;

    public ResourceDetails(
        ImmutableList<Path> resourceDirectories,
        ImmutableSet<Path> whitelistedStringDirectories,
        Supplier<ImmutableSet<String>> rDotJavaPackagesSupplier,
        boolean hasRDotJavaPackages,
        ImmutableList<BuildTarget> resourcesWithNonEmptyResDir,
        ImmutableList<BuildTarget> resourcesWithEmptyResButNonEmptyAssetsDir) {
      this.resourceDirectories = Preconditions.checkNotNull(resourceDirectories);
      this.whitelistedStringDirectories = Preconditions.checkNotNull(whitelistedStringDirectories);
      this.rDotJavaPackagesSupplier = Preconditions.checkNotNull(rDotJavaPackagesSupplier);
      this.hasRDotJavaPackages = hasRDotJavaPackages;
      this.resourcesWithNonEmptyResDir = Preconditions.checkNotNull(resourcesWithNonEmptyResDir);
      this.resourcesWithEmptyResButNonEmptyAssetsDir =
          Preconditions.checkNotNull(resourcesWithEmptyResButNonEmptyAssetsDir);
    }
  }

  public final ResourceDetails resourceDetails;

  /**
   * Directories containing native libraries.
   */
  public final ImmutableSet<Path> nativeLibsDirectories;

  /**
   * Directories containing native libraries to be used as assets.
   */
  public final ImmutableSet<Path> nativeLibAssetsDirectories;

  /**
   * Directories containing assets to be included directly in the apk,
   * under the "assets" directory.
   */
  public final ImmutableSet<Path> assetsDirectories;

  /**
   * AndroidManifest.xml files to be included in manifest merging.
   */
  public final ImmutableSet<Path> manifestFiles;

  /**
   * Proguard configurations to include when running release builds.
   */
  public final ImmutableSet<Path> proguardConfigs;

  /**
   * Java classes (jars) to include in the package.
   */
  public final ImmutableSet<Path> classpathEntriesToDex;

  /**
   * Java classes that were used during compilation, but don't got into the package.
   * This is only used by "buck project".  (It's existence is kind of contrary to
   * the purpose of this class, but we make exceptions for "buck project".)
   */
  public final ImmutableSet<Path> noDxClasspathEntries;

  public final ImmutableMap<String, BuildConfigFields> buildConfigs;

  /**
   * Prebuild/third-party jars to be included in the package.  For apks, their resources will
   * be placed directly in the apk.
   */
  public final ImmutableSet<Path> pathsToThirdPartyJars;

  /**
   * {@link com.facebook.buck.java.JavaLibrary} rules whose output will be dexed and included in
   * the package.
   */
  public final ImmutableSet<BuildTarget> javaLibrariesToDex;

  /**
   * See {@link com.facebook.buck.java.JavaLibrary#getClassNamesToHashes()}
   */
  public final Supplier<Map<String, HashCode>> classNamesToHashesSupplier;

  AndroidPackageableCollection(
      ResourceDetails resourceDetails,
      ImmutableSet<Path> nativeLibsDirectories,
      ImmutableSet<Path> nativeLibAssetsDirectories,
      ImmutableSet<Path> assetsDirectories,
      ImmutableSet<Path> manifestFiles,
      ImmutableSet<Path> proguardConfigs,
      ImmutableSet<Path> classpathEntriesToDex,
      ImmutableSet<Path> noDxClasspathEntries,
      ImmutableMap<String, BuildConfigFields> buildConfigs,
      ImmutableSet<Path> pathsToThirdPartyJars,
      ImmutableSet<BuildTarget> javaLibrariesToDex,
      Supplier<Map<String, HashCode>> classNamesToHashesSupplier) {
    this.resourceDetails = Preconditions.checkNotNull(resourceDetails);
    this.nativeLibsDirectories = Preconditions.checkNotNull(nativeLibsDirectories);
    this.nativeLibAssetsDirectories = Preconditions.checkNotNull(nativeLibAssetsDirectories);
    this.assetsDirectories = Preconditions.checkNotNull(assetsDirectories);
    this.manifestFiles = Preconditions.checkNotNull(manifestFiles);
    this.proguardConfigs = Preconditions.checkNotNull(proguardConfigs);
    this.classpathEntriesToDex = Preconditions.checkNotNull(classpathEntriesToDex);
    this.noDxClasspathEntries = Preconditions.checkNotNull(noDxClasspathEntries);
    this.buildConfigs = Preconditions.checkNotNull(buildConfigs);
    this.pathsToThirdPartyJars = Preconditions.checkNotNull(pathsToThirdPartyJars);
    this.javaLibrariesToDex = Preconditions.checkNotNull(javaLibrariesToDex);
    this.classNamesToHashesSupplier = Preconditions.checkNotNull(classNamesToHashesSupplier);
  }
}
