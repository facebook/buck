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

package com.facebook.buck.installer;

import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.Optional;

/** Represents an installableApk for a buck2 built apk */
class IsolatedApk implements HasInstallableApk {
  private final Path apkPath;
  private final String name;
  private final ProjectFilesystem filesystem;
  private final Path manifestPath;

  public IsolatedApk(Path apkPath, String name, ProjectFilesystem filesystem, Path manifestPath) {
    this.apkPath = apkPath;
    this.name = name;
    this.filesystem = filesystem;
    this.manifestPath = manifestPath;
  }

  @Override
  public ApkInfo getApkInfo() {
    return new ApkInfo() {
      @Override
      public SourcePath getManifestPath() {
        return PathSourcePath.of(getProjectFilesystem(), manifestPath);
      }

      @Override
      public SourcePath getApkPath() {
        return PathSourcePath.of(getProjectFilesystem(), apkPath);
      }

      @Override
      public Optional<ExopackageInfo> getExopackageInfo() {
        return Optional.empty();
      }
    };
  }

  @Override
  public BuildTarget getBuildTarget() {
    // TODO: pass this through JSON. For now use //android_install:{name}
    return UnconfiguredBuildTarget.of(BaseName.of("//android_install"), name)
        .configure(UnconfiguredTargetConfiguration.INSTANCE);
  }

  @Override
  public ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  public String getName() {
    return this.name;
  }
}
