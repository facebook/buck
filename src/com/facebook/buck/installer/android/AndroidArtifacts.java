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

package com.facebook.buck.installer.android;

import com.facebook.buck.core.filesystems.AbsPath;
import java.util.Optional;

/** Holds android install related artifacts (apk options, manifest path, etc) */
class AndroidArtifacts {
  private AbsPath androidManifestPath;
  private AndroidInstallApkOptions apkOptions;
  private AbsPath apk;
  private Optional<AbsPath> agentApk = Optional.empty();
  private Optional<AbsPath> secondaryDexExopackageInfoDirectory = Optional.empty();
  private Optional<AbsPath> secondaryDexExopackageInfoMetadata = Optional.empty();

  public void setAndroidManifestPath(AbsPath androidManifestPath) {
    this.androidManifestPath = androidManifestPath;
  }

  public AbsPath getAndroidManifestPath() {
    return this.androidManifestPath;
  }

  public void setApkOptions(AndroidInstallApkOptions apkOptions) {
    this.apkOptions = apkOptions;
  }

  public AndroidInstallApkOptions getApkOptions() {
    return this.apkOptions;
  }

  public AbsPath getApk() {
    return apk;
  }

  public void setApk(AbsPath apk) {
    this.apk = apk;
  }

  public Optional<AbsPath> getAgentApk() {
    return agentApk;
  }

  public void setAgentApk(Optional<AbsPath> agentApk) {
    this.agentApk = agentApk;
  }

  public Optional<AbsPath> getSecondaryDexExopackageInfoDirectory() {
    return secondaryDexExopackageInfoDirectory;
  }

  public void setSecondaryDexExopackageInfoDirectory(
      Optional<AbsPath> secondaryDexExopackageInfoDirectory) {
    this.secondaryDexExopackageInfoDirectory = secondaryDexExopackageInfoDirectory;
  }

  public Optional<AbsPath> getSecondaryDexExopackageInfoMetadata() {
    return secondaryDexExopackageInfoMetadata;
  }

  public void setSecondaryDexExopackageInfoMetadata(
      Optional<AbsPath> secondaryDexExopackageInfoMetadata) {
    this.secondaryDexExopackageInfoMetadata = secondaryDexExopackageInfoMetadata;
  }
}
