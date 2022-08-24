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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/** Holds android install related artifacts (apk options, manifest path, etc) */
class AndroidArtifacts {

  private final SettableFuture<AbsPath> androidManifestPath = SettableFuture.create();
  private final SettableFuture<AndroidInstallApkOptions> apkOptions = SettableFuture.create();

  public void setAndroidManifestPath(AbsPath androidManifestPath) {
    this.androidManifestPath.set(androidManifestPath);
  }

  public AbsPath getAndroidManifestPath() throws InterruptedException, ExecutionException {
    return this.androidManifestPath.get();
  }

  public void setApkOptions(AndroidInstallApkOptions apkOptions) {
    this.apkOptions.set(apkOptions);
  }

  public AndroidInstallApkOptions getApkOptions() throws InterruptedException, ExecutionException {
    return this.apkOptions.get();
  }

  public void waitTillReadyToUse() throws ExecutionException, InterruptedException {
    Futures.allAsList(Arrays.asList(androidManifestPath, apkOptions)).get();
  }
}
