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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

class AppleInstallAppOptions {
  public String fullyQualifiedName;
  public boolean useIdb = true;
  // "Use this option to set the platform an apple install"
  public String platformName = "iphonesimulator";
  public String xcodeDeveloperPath;
  public String deviceHelperPath;
  public Path infoPlistpath;
  /*
   *  Constructs options for an Apple Install that are set in apple_install_data.json artifact pass to the Installer
   */
  public AppleInstallAppOptions(Map<String, String> options) throws RuntimeException {
    if (options.get("label") != null) {
      this.fullyQualifiedName = options.get("label");
    }
    if (options.get("use_idb") != null) {
      this.useIdb = Boolean.parseBoolean(options.get("use_idb"));
    }
    if (options.get("platform_name") != null) {
      this.platformName = options.get("platform_name");
    }
    if (options.get("device_helper_path") != null) {
      this.deviceHelperPath = options.get("device_helper_path");
    }
    if (options.get("xcode_developer_path") == null) {
      throw new RuntimeException("xcode_developer_path must be set in apple_install_info.json");
    } else {
      this.xcodeDeveloperPath = options.get("xcode_developer_path");
    }
    if (options.get("info_plist") == null) {
      throw new RuntimeException("info_plist_path must be set in apple_install_info.json");
    } else {
      this.infoPlistpath = Paths.get(options.get("info_plist"));
    }
  }
}
