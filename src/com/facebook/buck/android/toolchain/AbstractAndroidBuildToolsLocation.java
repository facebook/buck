/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain;

import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable(copy = false)
@BuckStyleTuple
public interface AbstractAndroidBuildToolsLocation extends Toolchain {

  String DEFAULT_NAME = "android-build-tools";

  /** @return {@code Path} pointing to Android SDK build tools */
  Path getBuildToolsPath();

  /** @return {@code Path} pointing to Android SDK build tools bin directory */
  @Value.Derived
  default Path getBuildToolsBinPath() {
    // This is the directory under the Android SDK directory that contains the aapt, aidl, and
    // zipalign binaries. Before Android SDK Build-tools 23.0.0_rc1, this was the same as
    // buildToolsDir above.
    if (getBuildToolsPath().resolve("bin").toFile().exists()) {
      // Android SDK Build-tools >= 23.0.0_rc1 have executables under a new bin directory.
      return getBuildToolsPath().resolve("bin");
    } else {
      // Android SDK Build-tools < 23.0.0_rc1 have executables under the build-tools directory.
      return getBuildToolsPath();
    }
  }

  /** @return {@code Path} pointing to Android SDK aapt binary */
  @Value.Derived
  default Path getAaptPath() {
    return getBuildToolsBinPath().resolve("aapt");
  }
  /** @return {@code Path} pointing to Android SDK aapt2 binary */
  @Value.Derived
  default Path getAapt2Path() {
    return getBuildToolsBinPath().resolve("aapt2");
  }

  @Override
  default String getName() {
    return DEFAULT_NAME;
  }
}
