/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public class AndroidTransitiveDependencies {
  public final ImmutableSet<String> nativeLibsDirectories;
  public final ImmutableSet<String> nativeLibAssetsDirectories;
  public final ImmutableSet<String> assetsDirectories;
  public final ImmutableSet<BuildTarget> nativeTargetsWithAssets;
  public final ImmutableSet<String> manifestFiles;
  public final ImmutableSet<String> proguardConfigs;

  public AndroidTransitiveDependencies(
      ImmutableSet<String> nativeLibsDirectories,
      ImmutableSet<String> nativeLibAssetsDirectories,
      ImmutableSet<String> assetsDirectories,
      ImmutableSet<BuildTarget> nativeTargetsWithAssets,
      ImmutableSet<String> manifestFiles,
      ImmutableSet<String> proguardConfigs) {
    this.nativeLibsDirectories = Preconditions.checkNotNull(nativeLibsDirectories);
    this.nativeLibAssetsDirectories = Preconditions.checkNotNull(nativeLibAssetsDirectories);
    this.assetsDirectories = Preconditions.checkNotNull(assetsDirectories);
    this.nativeTargetsWithAssets = Preconditions.checkNotNull(nativeTargetsWithAssets);
    this.manifestFiles = Preconditions.checkNotNull(manifestFiles);
    this.proguardConfigs = Preconditions.checkNotNull(proguardConfigs);
  }
}
