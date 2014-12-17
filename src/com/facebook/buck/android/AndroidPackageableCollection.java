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

import com.facebook.buck.java.JavaNativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A collection of Android content that should be included in an Android package (apk or aar).
 */
@Value.Immutable
@Value.Nested
public interface AndroidPackageableCollection {

  @Value.Immutable
  public abstract static class ResourceDetails {

    /**
     * A list of "res" directories that should be passed to the aapt command to build the APK,
     * sorted topologically.
     */
    public abstract ImmutableList<Path> resourceDirectories();

    /**
     * A set of "res" directories that contain "whitelisted" strings, i.e. the strings-as-assets
     * resource filter does not affect these directories.
     */
    public abstract Set<Path> whitelistedStringDirectories();

    /**
     * A list of build targets belonging to {@link com.facebook.buck.android.AndroidResource}s with
     * non-empty "res" directory, sorted topologically. Note that these are {@link BuildTarget}s
     * to avoid introducing a circular dependency.
     */
    public abstract ImmutableList<BuildTarget> resourcesWithNonEmptyResDir();

    /**
     * Unlike {@link #resourcesWithNonEmptyResDir}, these resources only contain "assets".
     */
    public abstract Set<BuildTarget> resourcesWithEmptyResButNonEmptyAssetsDir();

    @Value.Derived
    public boolean hasResources() {
      return !resourceDirectories().isEmpty();
    }
  }

  ImmutableAndroidPackageableCollection.ResourceDetails resourceDetails();

  /**
   * A set of build targets that produce native libraries.
   */
  Set<BuildTarget> nativeLibsTargets();

  /**
   * Native libraries.
   */
  List<JavaNativeLinkable> nativeLinkables();

  /**
   * Directories containing native libraries.
   */
  Set<Path> nativeLibsDirectories();

  /**
   * Directories containing native libraries to be used as assets.
   */
  Set<Path> nativeLibAssetsDirectories();

  /**
   * Directories containing assets to be included directly in the apk,
   * under the "assets" directory.
   */
  Set<Path> assetsDirectories();

  /**
   * AndroidManifest.xml files to be included in manifest merging.
   */
  Set<Path> manifestFiles();

  /**
   * Proguard configurations to include when running release builds.
   */
  Set<Path> proguardConfigs();

  /**
   * Java classes (jars) to include in the package.
   */
  Set<Path> classpathEntriesToDex();

  /**
   * Java classes that were used during compilation, but don't got into the package.
   * This is only used by "buck project".  (It's existence is kind of contrary to
   * the purpose of this class, but we make exceptions for "buck project".)
   */
  Set<Path> noDxClasspathEntries();

  ImmutableMap<String, BuildConfigFields> buildConfigs();

  /**
   * Prebuild/third-party jars to be included in the package.  For apks, their resources will
   * be placed directly in the apk.
   */
  Set<Path> pathsToThirdPartyJars();

  /**
   * {@link com.facebook.buck.java.JavaLibrary} rules whose output will be dexed and included in
   * the package.
   */
  Set<BuildTarget> javaLibrariesToDex();

  /**
   * See {@link com.facebook.buck.java.JavaLibrary#getClassNamesToHashes()}
   */
  Supplier<Map<String, HashCode>> classNamesToHashesSupplier();
}
