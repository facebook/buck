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

import static com.facebook.buck.android.BinaryType.AAB;

import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** The class executes all binary steps responsible for optimizing aab specific components. */
public class AndroidBundleOptimizer extends AndroidBinaryOptimizer {

  AndroidBundleOptimizer(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      AndroidSdkLocation androidSdkLocation,
      AndroidPlatformTarget androidPlatformTarget,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      Optional<RedexOptions> redexOptions,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      Optional<CompressionAlgorithm> assetCompressionAlgorithm,
      boolean isCompressResources) {
    super(
        buildTarget,
        filesystem,
        androidSdkLocation,
        androidPlatformTarget,
        keystorePath,
        keystorePropertiesPath,
        redexOptions,
        packageAssetLibraries,
        compressAssetLibraries,
        assetCompressionAlgorithm,
        isCompressResources,
        AAB);
  }

  @Override
  void getBinaryTypeSpecificBuildSteps(
      ImmutableList.Builder<Step> steps,
      Path apkToAlign,
      Path finalApkPath,
      Supplier<KeystoreProperties> keystoreProperties,
      boolean applyRedex) {
    steps.add(
        new ZipalignStep(
            filesystem.getRootPath(), androidPlatformTarget, apkToAlign, finalApkPath));
  }
}
