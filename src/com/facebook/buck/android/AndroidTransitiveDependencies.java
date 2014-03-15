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

import java.nio.file.Path;

public class AndroidTransitiveDependencies {
  public final ImmutableSet<Path> nativeLibsDirectories;
  public final ImmutableSet<Path> nativeLibAssetsDirectories;
  public final ImmutableSet<Path> assetsDirectories;
  public final ImmutableSet<BuildTarget> nativeTargetsWithAssets;
  public final ImmutableSet<Path> manifestFiles;
  public final ImmutableSet<Path> proguardConfigs;

  public static final AndroidTransitiveDependencies EMPTY =
      new AndroidTransitiveDependencies(
          ImmutableSet.<Path>of(),
          ImmutableSet.<Path>of(),
          ImmutableSet.<Path>of(),
          ImmutableSet.<BuildTarget>of(),
          ImmutableSet.<Path>of(),
          ImmutableSet.<Path>of());

  public AndroidTransitiveDependencies(
      ImmutableSet<Path> nativeLibsDirectories,
      ImmutableSet<Path> nativeLibAssetsDirectories,
      ImmutableSet<Path> assetsDirectories,
      ImmutableSet<BuildTarget> nativeTargetsWithAssets,
      ImmutableSet<Path> manifestFiles,
      ImmutableSet<Path> proguardConfigs) {
    this.nativeLibsDirectories = Preconditions.checkNotNull(nativeLibsDirectories);
    this.nativeLibAssetsDirectories = Preconditions.checkNotNull(nativeLibAssetsDirectories);
    this.assetsDirectories = Preconditions.checkNotNull(assetsDirectories);
    this.nativeTargetsWithAssets = Preconditions.checkNotNull(nativeTargetsWithAssets);
    this.manifestFiles = Preconditions.checkNotNull(manifestFiles);
    this.proguardConfigs = Preconditions.checkNotNull(proguardConfigs);
  }
}
