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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;


/**
 * An object that represents the resources prebuilt native library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * prebuilt_native_library(
 *   name = 'face_dot_com',
 *   native_libs = 'nativeLibs',
 * )
 * </pre>
 */
public class PrebuiltNativeLibrary extends AbstractBuildRule
    implements NativeLibraryBuildRule, AndroidPackageable {

  private final boolean isAsset;
  private final Path libraryPath;
  private final ImmutableSortedSet<Path> librarySources;

  protected PrebuiltNativeLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path nativeLibsDirectory,
      boolean isAsset,
      ImmutableSortedSet<Path> librarySources) {
    super(params, resolver);
    this.isAsset = isAsset;
    this.libraryPath = nativeLibsDirectory;
    this.librarySources = librarySources;
  }

  @Override
  public boolean isAsset() {
    return isAsset;
  }

  @Override
  public Path getLibraryPath() {
    return libraryPath;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("is_asset", isAsset());
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return librarySources;
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    // A prebuilt_native_library does not have a "primary output" at this time.
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // We're checking in prebuilt libraries for now, so this is a noop.
    return ImmutableList.of();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeclaredDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (isAsset) {
      collector.addNativeLibAssetsDirectory(getLibraryPath());
    } else {
      collector.addNativeLibsDirectory(getBuildTarget(), getLibraryPath());
    }
  }
}
