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
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

public class AndroidBinaryFilesInfo {
  private final AndroidGraphEnhancementResult enhancementResult;
  private final EnumSet<ExopackageMode> exopackageModes;
  private final boolean packageAssetLibraries;

  public AndroidBinaryFilesInfo(
      AndroidGraphEnhancementResult enhancementResult,
      EnumSet<ExopackageMode> exopackageModes,
      boolean packageAssetLibraries) {
    this.enhancementResult = enhancementResult;
    this.exopackageModes = exopackageModes;
    this.packageAssetLibraries = packageAssetLibraries;
  }

  DexFilesInfo getDexFilesInfo() {
    DexFilesInfo dexFilesInfo = enhancementResult.getDexFilesInfo();
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      dexFilesInfo =
          new DexFilesInfo(
              dexFilesInfo.primaryDexPath,
              ImmutableSortedSet.of(),
              dexFilesInfo.proguardTextFilesPath,
              ImmutableMap.of());
    }
    return dexFilesInfo;
  }

  NativeFilesInfo getNativeFilesInfo() {
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        enhancementResult.getCopyNativeLibraries();

    boolean exopackageForNativeEnabled = ExopackageMode.enabledForNativeLibraries(exopackageModes);
    Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsDirs;
    if (exopackageForNativeEnabled) {
      nativeLibsDirs = Optional.empty();
    } else {
      nativeLibsDirs =
          copyNativeLibraries.map(
              cnl ->
                  cnl.entrySet().stream()
                      .collect(
                          ImmutableSortedMap.toImmutableSortedMap(
                              Ordering.natural(),
                              e -> e.getKey(),
                              e -> e.getValue().getSourcePathToNativeLibsDir())));
    }

    Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsAssetsDirs =
        copyNativeLibraries.map(
            cnl ->
                cnl.entrySet().stream()
                    .filter(
                        entry ->
                            !exopackageForNativeEnabled
                                || packageAssetLibraries
                                || !entry.getKey().isRootModule())
                    .collect(
                        ImmutableSortedMap.toImmutableSortedMap(
                            Ordering.natural(),
                            e -> e.getKey(),
                            e -> e.getValue().getSourcePathToNativeLibsAssetsDir())));
    return new NativeFilesInfo(nativeLibsDirs, nativeLibsAssetsDirs);
  }

  ResourceFilesInfo getResourceFilesInfo() {
    return new ResourceFilesInfo(
        ImmutableSortedSet.copyOf(
            enhancementResult.getPackageableCollection().getPathsToThirdPartyJars().values()),
        enhancementResult.getPrimaryResourcesApkPath(),
        enhancementResult.getPrimaryApkAssetZips());
  }

  Optional<ExopackageInfo> getExopackageInfo() {
    boolean shouldInstall = false;

    ExopackageInfo.Builder builder = ExopackageInfo.builder();
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      PreDexMerge preDexMerge = enhancementResult.getPreDexMerge().get();
      builder.setDexInfo(
          ExopackageInfo.DexInfo.of(
              preDexMerge.getMetadataTxtSourcePath(), preDexMerge.getDexDirectorySourcePath()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForModules(exopackageModes)) {
      PreDexMerge preDexMerge = enhancementResult.getPreDexMerge().get();

      ImmutableList<DexInfo> moduleInfo =
          preDexMerge
              .getModuleMetadataAndDexSourcePaths()
              .map(
                  metadataPathAndDexdir ->
                      DexInfo.of(
                          metadataPathAndDexdir.getFirst(), metadataPathAndDexdir.getSecond()))
              .collect(ImmutableList.toImmutableList());
      builder.setModuleInfo(moduleInfo);
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && enhancementResult.getCopyNativeLibraries().isPresent()) {
      CopyNativeLibraries copyNativeLibraries =
          Objects.requireNonNull(
              enhancementResult
                  .getCopyNativeLibraries()
                  .get()
                  .get(enhancementResult.getAPKModuleGraph().getRootAPKModule()));
      builder.setNativeLibsInfo(
          ExopackageInfo.NativeLibsInfo.of(
              copyNativeLibraries.getSourcePathToMetadataTxt(),
              copyNativeLibraries.getSourcePathToAllLibsDir()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForResources(exopackageModes)) {
      Preconditions.checkState(!enhancementResult.getExoResources().isEmpty());
      builder.setResourcesInfo(
          ExopackageInfo.ResourcesInfo.of(enhancementResult.getExoResources()));
      shouldInstall = true;
    } else {
      Preconditions.checkState(enhancementResult.getExoResources().isEmpty());
    }

    if (!shouldInstall) {
      return Optional.empty();
    }

    ExopackageInfo exopackageInfo = builder.build();
    return Optional.of(exopackageInfo);
  }
}
