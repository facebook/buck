/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class IjAndroidHelper {

  /**
   * This directory is analogous to the gen/ directory that IntelliJ would produce when building an
   * Android module. It contains files such as R.java, BuildConfig.java, and Manifest.java.
   *
   * <p>By default, IntelliJ generates its gen/ directories in our source tree, which would likely
   * mess with the user's use of {@code glob(['**&#x2f;*.java'])}. For this reason, we encourage
   * users to target
   */
  public static String getAndroidGenDir(ProjectFilesystem filesystem) {
    return MorePaths.pathWithUnixSeparators(
        filesystem.getBuckPaths().getBuckOut().resolve("android"));
  }

  public static String getAndroidApkDir(ProjectFilesystem filesystem) {
    return filesystem.getBuckPaths().getGenDir().toString();
  }

  public static Path createAndroidGenPath(
      ProjectFilesystem projectFilesystem, Path moduleBasePath) {
    return Paths.get(getAndroidGenDir(projectFilesystem)).resolve(moduleBasePath).resolve("gen");
  }

  private IjAndroidHelper() {}
}
