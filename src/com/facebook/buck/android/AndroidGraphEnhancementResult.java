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

import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
public interface AndroidGraphEnhancementResult {
  ImmutableAndroidPackageableCollection packageableCollection();
  AaptPackageResources aaptPackageResources();
  Optional<CopyNativeLibraries> copyNativeLibraries();
  Optional<PackageStringAssets> packageStringAssets();
  Optional<PreDexMerge> preDexMerge();
  Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi();

  /**
   * This includes everything from the corresponding
   * {@link AndroidPackageableCollection#classpathEntriesToDex}, and may include additional
   * entries due to {@link AndroidBuildConfig}s.
   */
  ImmutableSet<Path> classpathEntriesToDex();

  ImmutableSortedSet<BuildRule> finalDeps();
}
