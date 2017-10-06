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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * An object that represents the resources prebuilt native library.
 *
 * <p>Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 *
 * <pre>
 * prebuilt_native_library(
 *   name = 'face_dot_com',
 *   native_libs = 'nativeLibs',
 * )
 * </pre>
 */
public class PrebuiltNativeLibrary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements NativeLibraryBuildRule, AndroidPackageable {

  @AddToRuleKey private final boolean isAsset;
  private final Path libraryPath;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<? extends SourcePath> librarySources;

  protected PrebuiltNativeLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Path nativeLibsDirectory,
      boolean isAsset,
      ImmutableSortedSet<? extends SourcePath> librarySources) {
    super(buildTarget, projectFilesystem, params);
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
  @Nullable
  public SourcePath getSourcePathToOutput() {
    // A prebuilt_native_library does not have a "primary output" at this time.
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
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
      collector.addNativeLibAssetsDirectory(
          getBuildTarget(), PathSourcePath.of(getProjectFilesystem(), getLibraryPath()));
    } else {
      collector.addNativeLibsDirectory(
          getBuildTarget(), PathSourcePath.of(getProjectFilesystem(), getLibraryPath()));
    }
  }
}
