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

import com.android.apksig.ApkSigner;
import com.facebook.buck.android.apk.ApkSignerUtils;
import com.facebook.buck.android.apk.KeystoreProperties;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Use Google apksigner to v1/v2/v3 sign the final APK */
class ApkSignerStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path inputApkPath;
  private final Path outputApkPath;
  private final Supplier<KeystoreProperties> keystorePropertiesSupplier;

  public ApkSignerStep(
      ProjectFilesystem filesystem,
      Path inputApkPath,
      Path outputApkPath,
      Supplier<KeystoreProperties> keystorePropertiesSupplier) {
    this.filesystem = filesystem;
    this.inputApkPath = inputApkPath;
    this.outputApkPath = outputApkPath;
    this.keystorePropertiesSupplier = keystorePropertiesSupplier;
  }

  @Override
  public StepExecutionResult execute(StepExecutionContext context) {
    try {
      KeystoreProperties keystoreProperties = keystorePropertiesSupplier.get();
      ImmutableList<ApkSigner.SignerConfig> signerConfigs =
          ApkSignerUtils.getSignerConfigs(
              keystoreProperties,
              filesystem.getInputStreamForRelativePath(keystoreProperties.getKeystore()));
      File inputApkFile = filesystem.getPathForRelativePath(inputApkPath).toFile();
      File outputApkFile = filesystem.getPathForRelativePath(outputApkPath).toFile();
      ApkSignerUtils.signApkFile(inputApkFile, outputApkFile, signerConfigs);
    } catch (Exception e) {
      context.logError(e, "Error when signing APK at: %s.", outputApkPath);
      return StepExecutionResults.ERROR;
    }
    return StepExecutionResults.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "apk_signer";
  }

  @Override
  public String getDescription(StepExecutionContext context) {
    return getShortName();
  }
}
