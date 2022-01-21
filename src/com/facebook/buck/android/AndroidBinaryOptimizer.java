/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.android.apk.KeystoreProperties;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** The class executes all common binary steps responsible for optimizing aab/apk. */
public abstract class AndroidBinaryOptimizer implements AddsToRuleKey {

  @AddToRuleKey final boolean packageAssetLibraries;
  @AddToRuleKey final boolean compressAssetLibraries;
  @AddToRuleKey final Optional<CompressionAlgorithm> assetCompressionAlgorithm;

  @AddToRuleKey private final SourcePath keystorePath;
  @AddToRuleKey private final SourcePath keystorePropertiesPath;

  // Post-process resource compression
  @AddToRuleKey private final boolean isCompressResources;

  protected final BinaryType binaryType;

  // The zipalign tool.
  @AddToRuleKey protected final Tool zipalignTool;

  @AddToRuleKey protected final boolean withDownwardApi;

  // These should be the only things not added to the rulekey.
  protected final ProjectFilesystem filesystem;
  protected final BuildTarget buildTarget;
  protected final AndroidSdkLocation androidSdkLocation;

  AndroidBinaryOptimizer(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      AndroidSdkLocation androidSdkLocation,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      Optional<CompressionAlgorithm> assetCompressionAlgorithm,
      boolean isCompressResources,
      Tool zipalignTool,
      BinaryType binaryType,
      boolean withDownwardApi) {
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;
    this.androidSdkLocation = androidSdkLocation;
    this.keystorePath = keystorePath;
    this.keystorePropertiesPath = keystorePropertiesPath;
    this.isCompressResources = isCompressResources;
    this.packageAssetLibraries = packageAssetLibraries;
    this.compressAssetLibraries = compressAssetLibraries;
    this.assetCompressionAlgorithm = assetCompressionAlgorithm;
    this.zipalignTool = zipalignTool;
    this.binaryType = binaryType;
    this.withDownwardApi = withDownwardApi;
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    SourcePathResolverAdapter resolver = context.getSourcePathResolver();

    Path signedApkPath =
        AndroidBinaryPathUtility.getSignedApkPath(filesystem, buildTarget, binaryType);

    Path apkToAlign;
    // Optionally, compress the resources file in the .apk.
    if (isCompressResources) {
      Path compressedApkPath =
          AndroidBinaryPathUtility.getCompressedResourcesApkPath(
              filesystem, buildTarget, binaryType);
      apkToAlign = compressedApkPath;
      steps.add(createRepackZipEntriesStep(signedApkPath, compressedApkPath));
    } else {
      apkToAlign = signedApkPath;
    }

    Path v2SignedApkPath =
        AndroidBinaryPathUtility.getFinalApkPath(filesystem, buildTarget, binaryType);

    Path pathToKeystore = resolver.getAbsolutePath(keystorePath).getPath();
    Supplier<KeystoreProperties> keystoreProperties =
        getKeystorePropertiesSupplier(resolver, pathToKeystore);

    getBinaryTypeSpecificBuildSteps(
        steps, apkToAlign, v2SignedApkPath, keystoreProperties, context);
    buildableContext.recordArtifact(v2SignedApkPath);
    return steps.build();
  }

  private RepackZipEntriesStep createRepackZipEntriesStep(
      Path signedApkPath, Path compressedApkPath) {
    return new RepackZipEntriesStep(
        filesystem, signedApkPath, compressedApkPath, ImmutableSet.of("resources.arsc"));
  }

  private Supplier<KeystoreProperties> getKeystorePropertiesSupplier(
      SourcePathResolverAdapter resolver, Path pathToKeystore) {
    return MoreSuppliers.memoize(
        () -> {
          try {
            return KeystoreProperties.createFromPropertiesFile(
                pathToKeystore,
                filesystem.getPathForRelativePath(
                    resolver.getAbsolutePath(keystorePropertiesPath).getPath()));
          } catch (IOException e) {
            throw new RuntimeException();
          }
        });
  }

  abstract void getBinaryTypeSpecificBuildSteps(
      Builder<Step> steps,
      Path apkToAlign,
      Path finalApkPath,
      Supplier<KeystoreProperties> keystoreProperties,
      BuildContext context);
}
