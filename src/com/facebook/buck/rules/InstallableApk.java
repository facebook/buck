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

package com.facebook.buck.rules;

import com.google.common.base.Optional;

import java.nio.file.Path;

public interface InstallableApk extends BuildRule {

  /**
   * @return the path to the AndroidManifest.xml. Note that this file might be a symlink,
   *     and might not exist at all before this rule has been built.
   */
  public Path getManifestPath();

  /**
   * @return The APK at this path is the final one that points to an APK that a user should
   *         install.
   */
  public Path getApkPath();

  public Optional<ExopackageInfo> getExopackageInfo();

}
