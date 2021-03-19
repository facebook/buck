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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The class is responsible to create final bundle by taking individual module information and
 * passing to {@link com.android.bundle.Config.Bundletool} through {@link AabBuilderStep}.
 */
public class AndroidBundleBuildable extends AndroidBinaryBuildable {

  AndroidBundleBuildable(
    BuildTarget buildTarget,
    ProjectFilesystem filesystem,
    AndroidSdkLocation androidSdkLocation,
    SourcePath keystorePath,
    SourcePath keystorePropertiesPath,
    EnumSet<ExopackageMode> exopackageModes,
    int xzCompressionLevel,
    boolean packageAssetLibraries,
    boolean compressAssetLibraries,
    Optional<CompressionAlgorithm> assetCompressionAlgorithm,
    Tool javaRuntimeLauncher,
    SourcePath androidManifestPath,
    DexFilesInfo dexFilesInfo,
    NativeFilesInfo nativeFilesInfo,
    ResourceFilesInfo resourceFilesInfo,
    ImmutableSortedSet<APKModule> apkModules,
    ImmutableMap<APKModule, SourcePath> moduleResourceApkPaths,
    Optional<SourcePath> bundleConfigFilePath,
    BinaryType binaryType,
    boolean useDynamicFeature) {
    super(
      buildTarget,
      filesystem,
      androidSdkLocation,
      keystorePath,
      keystorePropertiesPath,
      exopackageModes,
      xzCompressionLevel,
      packageAssetLibraries,
      compressAssetLibraries,
      assetCompressionAlgorithm,
      javaRuntimeLauncher,
      androidManifestPath,
      dexFilesInfo,
      nativeFilesInfo,
      resourceFilesInfo,
      apkModules,
      moduleResourceApkPaths,
      bundleConfigFilePath,
      binaryType,
      useDynamicFeature);
  }

  @Override
  void getBinaryTypeSpecificBuildSteps(
    ImmutableList.Builder<Step> steps,
    ImmutableModuleInfo.Builder baseModuleInfo,
    Supplier<KeystoreProperties> keystoreProperties,
    ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder,
    ImmutableSet<Path> allAssetDirectories,
    SourcePathResolverAdapter pathResolver,
    ImmutableSet<Path> thirdPartyJars,
    ImmutableSet.Builder<Path> zipFiles,
    ImmutableSet.Builder<ModuleInfo> modulesInfo) {

    addAdditionalDexes(baseModuleInfo, pathResolver);

    baseModuleInfo
      .setResourceApk(pathResolver.getAbsolutePath(resourceFilesInfo.resourcesApkPath))
      .addDexFile(pathResolver.getRelativePath(dexFilesInfo.primaryDexPath))
      .setJarFilesThatMayContainResources(thirdPartyJars)
      .setZipFiles(zipFiles.build());

    modulesInfo.add(baseModuleInfo.build());

    Optional<Path> bundleConfigPath = bundleConfigFilePath.map(pathResolver::getAbsolutePath);

    steps.add(
      new AabBuilderStep(
        getProjectFilesystem(),
        AndroidBinaryPathUtility.getSignedApkPath(filesystem, buildTarget, binaryType),
        bundleConfigPath,
        buildTarget,
        false,
        modulesInfo.build()));
  }

  private void addAdditionalDexes(
    ImmutableModuleInfo.Builder baseModuleInfo, SourcePathResolverAdapter pathResolver) {
    ImmutableSet<String> moduleNames =
      apkModules.stream().map(APKModule::getName).collect(ImmutableSet.toImmutableSet());

    for (Path path : dexFilesInfo.getSecondaryDexDirs(getProjectFilesystem(), pathResolver)) {
      if (path.getFileName().toString().equals("additional_dexes")) {
        File[] assetFiles = path.toFile().listFiles();
        if (assetFiles == null) {
          continue;
        }
        for (File assetFile : assetFiles) {
          if (!assetFile.getName().equals("assets")) {
            continue;
          }
          File[] modules = assetFile.listFiles();
          if (modules == null) {
            continue;
          }
          for (File module : modules) {
            if (moduleNames.contains(module.getName())) {
              continue;
            }
            baseModuleInfo.putAssetDirectories(module.toPath(), "assets");
          }
        }
      } else {
        baseModuleInfo.putAssetDirectories(path, "");
      }
    }
  }
}
