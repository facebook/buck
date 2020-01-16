/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.Optional;

/**
 * Build rule that generates an APK that can be installed with the install command.
 *
 * @see com.facebook.buck.cli.InstallCommand
 */
public interface HasInstallableApk {
  ApkInfo getApkInfo();

  BuildTarget getBuildTarget();

  ProjectFilesystem getProjectFilesystem();

  @BuckStyleValue
  abstract class ApkInfo {
    /**
     * @return the path to the AndroidManifest.xml. Note that this file might be a symlink, and
     *     might not exist at all before this rule has been built.
     */
    public abstract SourcePath getManifestPath();

    /**
     * @return The APK at this path is the final one that points to an APK that a user should
     *     install.
     */
    public abstract SourcePath getApkPath();

    public abstract Optional<ExopackageInfo> getExopackageInfo();

    public ApkInfo withApkPath(SourcePath apkPath) {
      return ImmutableApkInfo.of(getManifestPath(), apkPath, getExopackageInfo());
    }
  }
}
