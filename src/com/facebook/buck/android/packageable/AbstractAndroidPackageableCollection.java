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
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.util.Set;
import java.util.function.Supplier;
import org.immutables.value.Value;

/** A collection of Android content that should be included in an Android package (apk or aar). */
@Value.Immutable
@Value.Enclosing
@BuckStyleImmutable
interface AbstractAndroidPackageableCollection {

  @Value.Immutable
  abstract class AbstractResourceDetails {

    /**
     * A list of "res" directories that should be passed to the aapt command to build the APK,
     * sorted topologically.
     */
    public abstract ImmutableList<SourcePath> getResourceDirectories();

    /**
     * A set of "res" directories that contain "whitelisted" strings, i.e. the strings-as-assets
     * resource filter does not affect these directories.
     */
    public abstract ImmutableSet<SourcePath> getWhitelistedStringDirectories();

    /**
     * A list of build targets belonging to {@link com.facebook.buck.android.AndroidResource}s with
     * non-empty "res" directory, sorted topologically. Note that these are {@link BuildTarget}s to
     * avoid introducing a circular dependency.
     */
    public abstract ImmutableList<BuildTarget> getResourcesWithNonEmptyResDir();

    /** Unlike {@link #getResourcesWithNonEmptyResDir}, these resources only contain "assets". */
    public abstract Set<BuildTarget> getResourcesWithEmptyResButNonEmptyAssetsDir();

    @Value.Derived
    public boolean hasResources() {
      return !getResourceDirectories().isEmpty();
    }
  }

  AndroidPackageableCollection.ResourceDetails getResourceDetails();

  /** Native libraries mapped from modules. */
  ImmutableMultimap<APKModule, NativeLinkable> getNativeLinkables();

  /** Native libraries to be packaged as assets. */
  ImmutableMultimap<APKModule, NativeLinkable> getNativeLinkablesAssets();

  /** Directories containing native libraries. */
  ImmutableMultimap<APKModule, SourcePath> getNativeLibsDirectories();

  /** Directories containing native libraries to be used as assets. */
  ImmutableMultimap<APKModule, SourcePath> getNativeLibAssetsDirectories();

  /**
   * Directories containing assets to be included directly in the apk, under the "assets" directory.
   */
  ImmutableSet<SourcePath> getAssetsDirectories();

  /** Proguard configurations to include when running release builds. */
  ImmutableSet<SourcePath> getProguardConfigs();

  /** Java classes (jars) to include in the package. */
  ImmutableSet<SourcePath> getClasspathEntriesToDex();

  /** Android manifests to merge with the manifest skeleton. */
  ImmutableSet<SourcePath> getAndroidManifestPieces();

  /** Java classes to include in the package sorted into modules */
  ImmutableMultimap<APKModule, SourcePath> getModuleMappedClasspathEntriesToDex();

  /**
   * Java classes that were used during compilation, but don't got into the package. This is only
   * used by "buck project". (It's existence is kind of contrary to the purpose of this class, but
   * we make exceptions for "buck project".)
   */
  ImmutableSet<SourcePath> getNoDxClasspathEntries();

  ImmutableMap<String, BuildConfigFields> getBuildConfigs();

  /**
   * Prebuilt/third-party jars to be included in the package. For apks, their resources will be
   * placed directly in the apk.
   */
  ImmutableSet<SourcePath> getPathsToThirdPartyJars();

  /** {@link JavaLibrary} rules whose output will be dexed and included in the package. */
  Set<BuildTarget> getJavaLibrariesToDex();

  /** See {@link JavaLibrary#getClassNamesToHashes()} */
  Supplier<ImmutableMap<String, HashCode>> getClassNamesToHashesSupplier();
}
