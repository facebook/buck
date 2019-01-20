/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackagePathAndHash;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
interface AbstractAndroidGraphEnhancementResult {
  AndroidPackageableCollection getPackageableCollection();

  Optional<ImmutableMap<APKModule, CopyNativeLibraries>> getCopyNativeLibraries();

  Optional<PackageStringAssets> getPackageStringAssets();

  Either<PreDexMerge, NonPreDexedDexBuildable> getDexMergeRule();

  SourcePath getPrimaryResourcesApkPath();

  ImmutableMap<APKModule, SourcePath> getModuleResourceApkPaths();

  ImmutableList<SourcePath> getPrimaryApkAssetZips();

  ImmutableList<ExopackagePathAndHash> getExoResources();

  /**
   * This includes everything from the corresponding {@link
   * AndroidPackageableCollection#getClasspathEntriesToDex}, and may include additional entries due
   * to {@link AndroidBuildConfig}s (or R.java, in the future).
   */
  ImmutableSet<SourcePath> getClasspathEntriesToDex();

  SourcePath getAndroidManifestPath();

  APKModuleGraph getAPKModuleGraph();

  @Value.Derived
  default Optional<PreDexMerge> getPreDexMerge() {
    return getDexMergeRule().transform(left -> Optional.of(left), right -> Optional.empty());
  }

  @Value.Derived
  default DexFilesInfo getDexFilesInfo() {
    return getDexMergeRule()
        .transform(PreDexMerge::getDexFilesInfo, NonPreDexedDexBuildable::getDexFilesInfo);
  }

  @Value.Derived
  default ImmutableList<SourcePath> getAdditionalRedexInputs() {
    return getDexMergeRule()
        .transform(left -> ImmutableList.of(), right -> right.getAdditionalRedexInputs());
  }
}
